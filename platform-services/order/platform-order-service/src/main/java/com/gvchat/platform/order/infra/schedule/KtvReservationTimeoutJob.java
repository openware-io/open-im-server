package com.gvchat.platform.order.infra.schedule;

import com.gvchat.platform.order.application.KtvReservationTimeoutService;
import com.gvchat.platform.order.application.KtvReservationTimeoutService.SweepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约超时（未到店）巡检任务（2026-09-20）。
 *
 * <p>背景：门店实测「预约超时 → 预约单已取消，但订单没开台、包厢一直不释放」——旧实现里 NO_SHOW 只能人工点，
 * 且历史孤儿会话（订单作废但会话仍 {@code RESERVED}）没有任何兜底，房态永远「已预订」。
 *
 * <p>口径：**到店时间 + 宽限期**（{@code ktv.reservation-timeout.grace-minutes}，默认 60 分钟）仍未到店 →
 * 释放包厢（取消未开台订单，内部级联释放占用）并给预约标「未到店」；有收款的订单不擅自释放，留给人工。
 *
 * <p>沿用仓内既有调度范式（{@code @Scheduled(fixedDelayString = "${键:默认值}")} + 开关配置，见
 * {@code KtvRoomFeeRefreshJob} / {@code MemberNameIndexRebuildJob}）：逐条 try-catch，单条失败不中断整批，
 * 异常不抛出到调度线程（下一轮重试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KtvReservationTimeoutJob {

    private final KtvReservationTimeoutService timeoutService;

    /** 开关：本地联调/压测可关掉，避免后台写库干扰。 */
    @Value("${ktv.reservation-timeout.enabled:true}")
    private boolean enabled;

    /** 宽限期（分钟）：到店时间之后多久算「未到店」。默认 60 分钟。 */
    @Value("${ktv.reservation-timeout.grace-minutes:60}")
    private int graceMinutes;

    @Scheduled(fixedDelayString = "${ktv.reservation-timeout.sweep-ms:300000}")
    public void sweepOverdueReservations() {
        if (!enabled) {
            return;
        }
        try {
            SweepResult result = timeoutService.sweep(graceMinutes);
            if (result.touched() > 0 || result.needsManual() > 0) {
                log.info("预约超时巡检：释放订单 {}，收敛孤儿会话 {}，未到店预约 {}，需人工 {}（宽限 {} 分钟）",
                        result.releasedOrders(), result.orphanSessions(), result.noShowReservations(),
                        result.needsManual(), graceMinutes);
            }
        } catch (RuntimeException failure) {
            log.error("预约超时巡检失败（下一轮重试）: cause={}", failure.getMessage(), failure);
        }
    }
}
