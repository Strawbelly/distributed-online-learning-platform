package com.tianji.learning.handler;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsRecordService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_RECORD_TABLE_PREFIX;

@Component
@RequiredArgsConstructor
public class PointsRecordMigrateHandler {

    private final IPointsBoardSeasonService seasonService;
    private final IPointsRecordService recordService;

    // 创建表
    @XxlJob("createRecordTableJob")
    public void createPointsRecordTableOfLastSeason() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            // 赛季不存在
            return;
        }
        // 3.创建表
        recordService.createPointsRecordTableBySeason(season);
    }

    // 数据迁移
    @XxlJob("savePointsRecord2DB")
    public void savePointsRecord2DB() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询赛季信息
        Integer season = seasonService.querySeasonByTime(time);
        // 3.查询积分数据
        int index = XxlJobHelper.getShardIndex(); // 当前实例是第几片
        int total = XxlJobHelper.getShardTotal(); // 总共有几片
        int pageNo = index + 1;
        int pageSize = 1000;
        while (true) {
            List<PointsRecord> boardList = recordService.queryRecordListByTime(time, pageNo, pageSize);
            if (CollUtils.isEmpty(boardList)) {
                break;
            }
            // 4.表名存入ThreadLocal
            TableInfoContext.setInfo(POINTS_RECORD_TABLE_PREFIX + season);
            // 4.持久化到数据库
            try {
                recordService.saveBatch(boardList);
            } finally {
                TableInfoContext.remove(); // 保存完立刻清除，避免影响下一次循环的查询
            }
            // 5.翻页
            pageNo += total;
        }
    }

    // 清理历史数据
    @XxlJob("clearPointsRecordFromDB")
    public void clearPointsRecordFromDB() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算上赛季的起止时间
        PointsBoardSeason boardSeason = seasonService.querySeason(time);
        LocalDateTime begin = DateUtils.getMonthBeginTime(boardSeason.getBeginTime());
        LocalDateTime end = DateUtils.getMonthEndTime(boardSeason.getBeginTime());

        // 3.按时间范围直接删除主表里的旧数据
        recordService.lambdaUpdate()
                .between(PointsRecord::getCreateTime, begin, end)
                .remove();
    }
}
