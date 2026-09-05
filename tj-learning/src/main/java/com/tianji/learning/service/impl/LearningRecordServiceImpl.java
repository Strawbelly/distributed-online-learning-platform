package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.api.dto.leanring.LearningRecordFormDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-12-10
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {
    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;

    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1. 获取登录用户
        Long userId = UserContext.getUser();
        // 2. 查询课表
        LearningLesson lesson = lessonService.queryByUserIdAndCourseId(userId, courseId);
        if (lesson == null) { // 存在未购买课程但试看视频的可能，课表可能为空
            return null;
        }
        // 3. 查询学习记录
        // select * from xx where lesson_id = #{lessonId}
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();
        // 4. 封装结果
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));
        return dto;
    }

    /**
     * Add/update learning record for exam or video section type
     * @param recordDTO
     */
    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.处理学习记录
        boolean finished = false;
        if (recordDTO.getSectionType() == SectionType.VIDEO.getValue()) {
            // 2.1 处理视频
            finished = handleVideoRecord(userId, recordDTO);
        } else {
            // 2.2 处理考试
            finished = handleExamRecord(userId, recordDTO);
        }
        if (!finished) {
            // 没有新学完的小节，无需更新课表中的学习进度
            return;
        }
        // 3.处理课表数据
        handleLearningLessonsChanges(recordDTO);
    }

//    private void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO, boolean finished) {
//        // 1.查询课表（已经学完的小节数量）
//        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
//        if (lesson == null) {
//            throw new BizIllegalException("课程不存在，无法更新数据！");
//        }
//        // 2.判断是否有新完成的小节
//        boolean allLearned = false;
//        if (finished) {
//            // 3.如果有新完成的小节，则需要查询课程数据（课程包含的小节数量）
//            CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
//            if (cInfo == null) {
//                throw new BizIllegalException("课程不存在，无法更新数据！");
//            }
//            // 4.比较课程是否全部学完，已学完小节 >= 课程总小节
//            allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();
//        }
//        // 5.更新课表
//        lessonService.lambdaUpdate()
//                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue()) // 第一次学习这个课程，更新学习状态为学习中
//                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue()) // 课程全部学完，更新学习状态为已学完
//                .set(!finished, LearningLesson::getLatestSectionId, recordDTO.getSectionId()) // 更新最近学习的小节id
//                .set(!finished, LearningLesson::getLatestLearnTime, recordDTO.getCommitTime()) // 更新最近的学习时间
//                .setSql(finished, "learned_sections = learned_sections + 1")
//                .eq(LearningLesson::getId, lesson.getId())
//                .update();
//    }

    /**
     * Update learning_lesson table
     * @param recordDTO
     */
    private void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO) {
        // 1.查询课表（已经学完的小节数量）
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 2.判断是否有新完成的小节
        boolean allLearned = false;
        // 3.如果有新完成的小节，则需要查询课程数据（课程包含的小节数量）
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 4.比较课程是否全部学完，已学完小节 >= 课程总小节
        allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();

        // 5.更新课表
        lessonService.lambdaUpdate()
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue()) // 第一次学习这个课程，更新学习状态为学习中
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue()) // 课程全部学完，更新学习状态为已学完
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    /**
     * Handle learning record for videos
     * @param userId
     * @param recordDTO
     * @return
     */
//    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
//        // 1.查询旧的学习记录
//        LearningRecord old = lambdaQuery()
//                .eq(LearningRecord::getLessonId, recordDTO.getLessonId())
//                .eq(LearningRecord::getSectionId, recordDTO.getSectionId())
//                .one();
//        // 2.判断是否存在
//        if (old == null) {
//            // 3.不存在，则新增
//            // 3.1 转换DTO为PO
//            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
//            // 3.2 填充数据
//            record.setUserId(userId);
//            // 3.3 写入数据库
//            boolean success = save(record);
//            if (!success) {
//                throw new DbException("新增学习记录失败！");
//            }
//            return false;
//        }
//        // 4.存在，则更新
//        // 4.1 判断是否是第一次完成
//        boolean finished = !old.getFinished() && recordDTO.getMoment() * 2 >= recordDTO.getDuration(); // 小节是未完成状态 + 小节学习进度>=50% = 已完成
//        // 4.2 更新数据
//        boolean success = lambdaUpdate()
//                .set(LearningRecord::getMoment, recordDTO.getMoment())
//                .set(finished, LearningRecord::getFinished, true)
//                .set(finished, LearningRecord::getFinishTime, recordDTO.getCommitTime())
//                .eq(LearningRecord::getId, old.getId())
//                .update();
//        if (!success) {
//            throw new DbException("更新学习记录失败！");
//        }
//        return finished;
//    }

    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.查询旧的学习记录
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        // 2.判断是否存在
        if (old == null) {
            // 3.不存在，则新增
            // 3.1 转换DTO为PO
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            // 3.2 填充数据
            record.setUserId(userId);
            // 3.3 写入数据库
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败！");
            }
            return false;
        }
        // 4.存在，则更新
        // 4.1 判断是否是第一次完成
        boolean finished = !old.getFinished() && recordDTO.getMoment() * 2 >= recordDTO.getDuration(); // 小节是未完成状态 + 小节学习进度>=50% = 已完成
        // 不是第一次完成
        if (!finished) {
            LearningRecord record = new LearningRecord();
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            record.setId(old.getId());
            record.setFinished(old.getFinished());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        // 4.2 更新数据
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(LearningRecord::getFinished, true)
                .set(LearningRecord::getFinishTime, recordDTO.getCommitTime())
                .eq(LearningRecord::getId, old.getId())
                .update();
        if (!success) {
            throw new DbException("更新学习记录失败！");
        }
        // 4. 3 清理缓存
        taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
        return true;
    }

    /**
     * Get the old learning record from cache
     * @param lessonId
     * @param sectionId
     * @return
     */
    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        // 1.查询缓存
        LearningRecord record = taskHandler.readRecordCache(lessonId, sectionId);
        // 2.如果命中，直接返回
        if (record != null) {
            return record;
        }
        // 3.未命中，查询数据库
        record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        // 4.写入缓存
        taskHandler.writeRecordCache(record);
        return record;
    }

    /**
     * Handle learning record for exams
     * @param userId
     * @param recordDTO
     * @return
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.转换DTO为PO
        LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
        // 2.填充数据
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(recordDTO.getCommitTime());
        // 3.写入数据库
        boolean success = save(record);
        if (!success) {
            throw new DbException("新增考试记录失败！");
        }
        return true;
    }
}
