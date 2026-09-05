package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.tianji.learning.constants.LearningConstants.POINTS_RECORD_TABLE_PREFIX;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;
    private final IPointsBoardSeasonService seasonService;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType type) {
        LocalDateTime now = LocalDateTime.now();
        // 1.判断当前方式有没有积分上限
        int maxPoints = type.getMaxPoints();
        int realPoints = points;
        if (maxPoints > 0) {
            // 2.有，则需要判断是否超过上限
            LocalDateTime begin = DateUtils.getDayStartTime(now);
            LocalDateTime end = DateUtils.getDayEndTime(now);
            // 2.1 查询今日已得积分
            int currentPoints = queryUserPointsByTypeAndDate(userId, type, begin, end);
            // 2.2 判断是否超过上限
            if (currentPoints >= maxPoints) {
                // 2.3 超过，直接结束
                return;
            }
            // 2.4 没超过，保存积分记录（细节：当前积分加上本次获得的积分不能超过积分上限，否则只会获得上限内的积分）
            if (currentPoints + points > maxPoints) {
                realPoints = maxPoints - currentPoints;
            }
        }
        // 3.没有，直接保存积分记录
        PointsRecord p = new PointsRecord();
        p.setPoints(realPoints);
        p.setUserId(userId);
        p.setType(type);
        save(p);
        // 4.累积积分数据到Redis的SortedSet中
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);
    }

    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        // 1.获取用户
        Long userId = UserContext.getUser();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);
        // 3.构建查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .between(PointsRecord::getCreateTime, begin, end);
        // 4.查询
        List<PointsRecord> list = getBaseMapper().queryUserPointsByDate(wrapper);
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        // 5.封装返回
        List<PointsStatisticsVO> vos = new ArrayList<>(list.size());
        for (PointsRecord p : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(p.getType().getDesc());
            vo.setMaxPoints(p.getType().getMaxPoints());
            vo.setPoints(p.getPoints());
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public void createPointsRecordTableBySeason(Integer season) {
        getBaseMapper().createPointsRecordTable(POINTS_RECORD_TABLE_PREFIX + season);
    }

    @Override
    public List<PointsRecord> queryRecordListByTime(LocalDateTime time, int pageNo, int pageSize) {
        // 1.查询上个赛季的起始时间
        PointsBoardSeason season = seasonService.querySeason(time);
        LocalDateTime begin = DateUtils.getMonthBeginTime(season.getBeginTime());
        LocalDateTime end = DateUtils.getMonthEndTime(season.getBeginTime());
        // 2.查询数据
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda().between(PointsRecord::getCreateTime, begin, end);
        Page<PointsRecord> page = page(new Page<>(pageNo, pageSize), wrapper);
        List<PointsRecord> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return CollUtils.emptyList();
        }
        // 3.返回结果
        return records;
    }

    private int queryUserPointsByTypeAndDate(
            Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        // 这里采用MybatisPlus构建查询条件、拼接手写SQL的方式
        // 1.构建查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(type != null, PointsRecord::getType, type) // 封装成一个单独的方法，需要做健壮性判断（至少要有userId，其他可能不传）
                .between(begin != null && end != null, PointsRecord::getCreateTime, begin, end);
        // 2.调用mapper，查询结果
        Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);
        // 3.判断并返回
        return points == null ? 0 : points;
    }
}
