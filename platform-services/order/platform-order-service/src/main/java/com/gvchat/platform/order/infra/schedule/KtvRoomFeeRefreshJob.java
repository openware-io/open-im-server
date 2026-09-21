package com.gvchat.platform.order.infra.schedule;

import com.gvchat.platform.order.application.KtvSessionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 开台中包厢房费刷新（2026-09-19）。
 *
 * <p>背景：开台即计费（{@code KtvSessionApplicationService#open} 写入 ROOM_FEE 明细），金额随时间增长。
 * 账单与看板本身取实时值（账单对 OPEN 会话按「此刻结台」计算、订单列表回 {@code roomEstimatedFee}），
 * 本任务负责让**库里**的 ROOM_FEE 明细与订单合计也跟上时间：
 * <ul>
 *   <li>修复本轮之前开台、房费明细缺失的存量订单（部署后一个周期内自动补齐）；</li>
 *   <li>长开台订单的合计不会长期停在旧值（报表/导出读的是库里金额）。</li>
 * </ul>
 *
 * <p>任务本身不抛异常（内部逐会话 try/catch + 外层兜底）：房费刷新失败绝不能影响开台/结台/收银，
 * 也不能让调度线程停摆；下一轮会重试。间隔可配（沿用仓内 {@code @Scheduled(fixedDelayString=...)} 范式），
 * 默认 5 分钟——结台时仍按 {@code closed_at} 精确重算，因此刷新间隔不影响最终收款金额。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KtvRoomFeeRefreshJob {

    private final KtvSessionApplicationService ktvSessionService;

    /** 开关：本地联调/压测可关掉，避免后台写库干扰。 */
    @Value("${ktv.room-fee.refresh-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${ktv.room-fee.refresh-ms:300000}")
    public void refreshOpenRoomFees() {
        if (!enabled) {
            return;
        }
        try {
            int refreshed = ktvSessionService.refreshOpenRoomFees();
            if (refreshed > 0) {
                log.info("已刷新开台中包厢房费: sessions={}", refreshed);
            }
        } catch (RuntimeException failure) {
            log.error("刷新开台中包厢房费失败（下一轮重试）: cause={}", failure.getMessage(), failure);
        }
    }
}
