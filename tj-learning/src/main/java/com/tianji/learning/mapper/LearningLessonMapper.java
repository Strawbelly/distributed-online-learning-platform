package com.tianji.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.LearningLesson;
import feign.Param;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-12-02
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {
    Integer queryTotalPlan(@Param("userId") Long userId);
}
