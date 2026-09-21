package com.gvchat.platform.resource.application;

import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 占用兜底释放定时任务：
 * <ol>
 *   <li>过期 HELD（{@code hold_expires_at} 已过）→ RELEASED：预占超时自动归还；</li>
 *   <li>业务时段已结束（{@code end_at} 已过）的有效占用 → RELEASED：结台/取消释放失败时自愈，
 *       避免房态长期卡在「使用中」导致包厢不能清洁完成、不能再次开台。</li>
 * </ol>
 * 两条都走乐观锁 version CAS。
 */
@Component
public class HoldExpiryScheduler {
    private final OccupationApplicationService occupationService;

    public HoldExpiryScheduler(OccupationApplicationService occupationService) {
        this.occupationService = occupationService;
    }

    @Scheduled(fixedDelayString = "${platform.resource.occupation.expiry-scan-delay-ms:60000}")
    public void releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now();
        occupationService.releaseExpiredHolds(now);
        occupationService.releaseEndedOccupations(now);
    }
}
