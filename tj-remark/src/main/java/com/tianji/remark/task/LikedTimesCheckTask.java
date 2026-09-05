package com.tianji.remark.task;

import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LikedTimesCheckTask {

    // TODO 业务类型应该放到配置文件中，将来交给nacos管理，万一业务类型变更，直接在nacos中修改，这样项目不需要重启就能生效
    private static final List<String> BIZ_TYPES = List.of("QA", "NOTE");
    private static final int MAX_BIZ_SIZE = 30; // 最大的业务数量

    private final ILikedRecordService recordService;

    @Scheduled(fixedDelay = 20000) // fixedDelay: 两个任务之间执行的间隔
    public void checkLikedTimes(){
        for (String bizType : BIZ_TYPES) {
            recordService.readLikedTimesAndSendMessage(bizType, MAX_BIZ_SIZE);
        }
    }
}