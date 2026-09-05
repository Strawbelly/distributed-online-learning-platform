//package com.tianji.learning.utils;
//
//import lombok.Data;
//
//import java.time.Duration;
//import java.util.concurrent.Delayed;
//import java.util.concurrent.TimeUnit;
//
//@Data
//public class DelayTask<D> implements Delayed {
//
//    // 这两个变量都是创建的时候通过构造函数指定
//    private D data; // 任务中包含的数据（任务类型不确定，因此用泛型表示）
//    private long deadlineNanos; // 任务的到期时间/执行时间
//
//    // 计算延迟任务的剩余有效期
//    public DelayTask(D data, Duration delayTime) {
//        this.data = data;
//        this.deadlineNanos = System.nanoTime() + delayTime.toNanos();
//    }
//
//    @Override
//    public long getDelay(TimeUnit unit) {
//        return unit.convert(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
//    }
//
//    @Override
//    public int compareTo(Delayed o) {
//        long l = getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS);
//        if(l > 0){
//            return 1;
//        }else if(l < 0){
//            return -1;
//        }else {
//            return 0;
//        }
//    }
//}
