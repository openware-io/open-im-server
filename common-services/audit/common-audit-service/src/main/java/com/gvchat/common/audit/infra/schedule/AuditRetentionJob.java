package com.gvchat.common.audit.infra.schedule;

import com.gvchat.common.audit.application.retention.AuditRetentionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计保留任务调度（方案 §5 批次 4）：按固定间隔执行一轮保留策略。
 *
 * <p>沿用仓内既有调度范式（{@code @Scheduled(fixedDelayString = "${键:默认值}")}，见
 * {@code MediaExpiryCleanupJob} 等）：间隔可配、默认 1 小时。
 *
 * <p>任务本身**不抛异常**（内部逐段 try/catch 并记 ERROR/WARN）：保留任务的失败绝不能影响审计写入，
 * 也不能让调度线程因未捕获异常而停摆；需要告警的点在服务里已按 ERROR/WARN 分级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private final AuditRetentionApplicationService service;

    @Value("${audit.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${audit.retention.interval-ms:3600000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            service.runOnce();
        } catch (RuntimeException failure) {
            log.error("审计保留任务本轮失败（下轮重试）: cause={}", failure.getMessage(), failure);
        }
    }
}
