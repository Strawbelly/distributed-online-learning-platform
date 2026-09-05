package com.tianji.promotion.handler;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.utils.CollUtils;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.service.ICouponService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CouponIssueTaskHandler {

    private final ICouponService couponService;

    @XxlJob("couponIssueJobHandler")
    public void handleCouponIssueJob(){
        // 1.获取分片信息（当前实例的index和分片总数），每页最多查询 20条
        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();
        int size = Integer.parseInt(XxlJobHelper.getJobParam());
        Long lastId = 0L;
        LocalDateTime now = LocalDateTime.now();
        // 2.查询<<未开始>>的优惠券
        while (true) {
            List<Coupon> coupons = couponService.lambdaQuery()
                    .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                    .apply("id % {0} = {1}", total, index)
                    .gt(Coupon::getId, lastId)
                    .le(Coupon::getIssueBeginTime, now)
                    .last(String.format("LIMIT %d", size))
                    .list();
            if (CollUtils.isEmpty(coupons)) {
                break;
            }
            // 3.发放优惠券
            couponService.beginIssueBatch(coupons);
            lastId = coupons.get(coupons.size() - 1).getId();
        }
    }

    @XxlJob("couponStopIssueJobHandler")
    public void handleCouponStopIssueJob(){
        // 1.获取分片信息（当前实例的index和分片总数），每页最多查询 20条
        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();
        int size = Integer.parseInt(XxlJobHelper.getJobParam());
        Long lastId = 0L;
        LocalDateTime now = LocalDateTime.now();
        // 2.查询<<进行中>>的优惠券
        while (true) {
            List<Coupon> coupons = couponService.lambdaQuery()
                    .eq(Coupon::getStatus, CouponStatus.ISSUING)
                    .apply("id % {0} = {1}", total, index)
                    .gt(Coupon::getId, lastId)
                    .le(Coupon::getIssueEndTime, now)
                    .last(String.format("LIMIT %d", size))
                    .list();
            if (CollUtils.isEmpty(coupons)) {
                break;
            }
            // 3.停止发放优惠券
            couponService.stopIssueBatch(coupons);
            lastId = coupons.get(coupons.size() - 1).getId();
        }
    }
}
