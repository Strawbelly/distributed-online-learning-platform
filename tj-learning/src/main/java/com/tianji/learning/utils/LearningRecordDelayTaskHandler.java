package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper; // LearningRecordService要调用我们的工具类，因此注入mapper防止循环依赖
    private final ILearningLessonService lessonService;
    private final RedissonClient redissonClient;

    private static final String QUEUE_NAME = "learning:record:delay:queue";
    private RBlockingQueue<RecordTaskData> blockingQueue;
    private RDelayedQueue<RecordTaskData> delayedQueue;

    private final static String RECORD_KEY_TEMPLATE = "learning:record:{}";

    private volatile boolean begin = true; // volatile关键字防止出现修改了这个变量但对其他线程不可见的情况

    // Executor -> ExecutorService，这样才能在 destroy() 里调用 shutdownNow()
    // 消费者线程数：这里开4个线程并发消费，真正利用上线程池
    private static final int CONSUMER_THREADS = 4;
    private final ExecutorService es = Executors.newFixedThreadPool(CONSUMER_THREADS);

    // 定义两个生命周期函数
    @PostConstruct // 在当前类被初始化，上面的Bean都完成注入之后，来调用init()
    public void init(){
        // 1.获取真正用于消费的阻塞队列
        blockingQueue = redissonClient.getBlockingQueue(QUEUE_NAME);
        // 2.基于阻塞队列创建延迟队列
        delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
        // 3. 提交4次任务，让线程池里的4个线程都参与消费，而不是只有1个线程在跑
        for (int i = 0; i < CONSUMER_THREADS; i++) {
            es.execute(this::handleDelayTask);
        }
    }

    @PreDestroy // 在整个容器销毁之前先调用destroy()，终止循环
    public void destroy(){
        begin = false;
        // shutdownNow() 会向阻塞在 take() 上的线程发送中断信号，
        // 使其抛出 InterruptedException，从而能真正跳出循环退出
        es.shutdownNow();
        log.debug("延迟任务停止执行！");
    }

    // 这个方法必须异步执行，如果init()直接调它会阻塞Spring的生命周期，整个项目就无法启动
    public void handleDelayTask(){
        while (begin) {
            try {
                // 1.获取到期的延迟任务
                RecordTaskData data = blockingQueue.take();
                // 2.查询Redis缓存
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                if (record == null) {
                    continue;
                }
                // 3.比较数据，moment值
                if(!Objects.equals(data.getMoment(), record.getMoment())) {
                    // 不一致，说明用户还在持续提交播放进度，放弃旧数据
                    continue;
                }
                // 4.一致，持久化播放进度数据到数据库
                // 4.1.更新学习记录的moment
                record.setFinished(null);
                recordMapper.updateById(record);
                // 4.2.更新课表最近学习信息
                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());
                lesson.setLatestSectionId(data.getSectionId());
                lesson.setLatestLearnTime(LocalDateTime.now()); // 忽略了延迟20秒的时间，将最近学习时间设为当前时间
                lessonService.updateById(lesson);
            } catch (InterruptedException e) {
                // 收到中断说明是 destroy() 触发的关闭信号，
                // 恢复中断状态并正常退出循环，不当异常打印
                Thread.currentThread().interrupt();
                log.debug("延迟任务线程收到中断，准备退出");
            } catch (Exception e) {
                log.error("处理延迟任务发生异常", e); // 不要抛出异常，否则会影响后续任务的执行
            }
        }
    }

    public void addLearningRecordTask(LearningRecord record){
        // 1.添加数据到Redis缓存
        writeRecordCache(record);
        // 2.提交延迟任务到延迟队列delayedQueue
        delayedQueue.offer(new RecordTaskData(record), 20, TimeUnit.SECONDS);
    }

    public void writeRecordCache(LearningRecord record) {
        log.debug("更新学习记录的缓存数据");
        try {
            // 1.数据转换
            String json = JsonUtils.toJsonStr(new RecordCacheData(record));
            // 2.写入Redis
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId());
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
            // 3.添加缓存过期时间
            redisTemplate.expire(key, Duration.ofMinutes(1));
        } catch (Exception e) {
            log.error("更新学习记录缓存异常", e);
        }
    }

    public LearningRecord readRecordCache(Long lessonId, Long sectionId){
        try {
            // 1.读取Redis数据
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {
                return null;
            }
            // 2.数据检查和转换
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            log.error("缓存读取异常", e);
            return null; // 返回null，代表缓存未命中
        }
    }

    public void cleanRecordCache(Long lessonId, Long sectionId){
        // 删除数据
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }

    @Data
    @NoArgsConstructor
    private static class RecordCacheData{
        private Long id;
        private Integer moment;
        private Boolean finished;

        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }
    @Data
    @NoArgsConstructor
    private static class RecordTaskData implements Serializable {
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}
