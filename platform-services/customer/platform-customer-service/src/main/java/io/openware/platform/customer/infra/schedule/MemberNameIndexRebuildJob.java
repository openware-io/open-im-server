package io.openware.platform.customer.infra.schedule;

import io.openware.platform.customer.application.MemberApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客户姓名盲索引存量回填任务（2026-09-19）。
 *
 * <p>背景：姓名是随机 IV 的 AES-256-GCM 密文，迁移里无法解密，因此历史客户的
 * {@code cst_member_name_token} 只能在应用层回填。任务只处理「**没有 token 行**」的客户
 * （幂等、可重入）：部署后一个周期内自动补齐，之后每轮几乎是空查询。
 *
 * <p>沿用仓内既有调度范式（{@code @Scheduled(fixedDelayString = "${键:默认值}")} + 开关配置，
 * 见 {@code KtvRoomFeeRefreshJob}、{@code AuditRetentionJob}）。逐租户处理、逐条 try/catch：
 * <b>绝不因为单条/单租户失败中断整批</b>，也不让调度线程因未捕获异常停摆（下一轮重试）。
 * 租户上下文的设置与还原在 {@code MemberApplicationService#rebuildNameIndexForTenant} 内完成
 * （不设上下文会被租户拦截器整批拒绝）。
 *
 * <p>默认 30 分钟一轮；存量回填完成后可把 {@code member.name-index.rebuild-enabled} 置为 false
 * 关掉（返回结果里的 {@code remaining} 为 0 即为回填完毕），避免无谓查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberNameIndexRebuildJob {

    private final MemberApplicationService memberService;

    /** 开关：本地联调/压测或回填完成后可关掉。 */
    @Value("${member.name-index.rebuild-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${member.name-index.rebuild-ms:1800000}")
    public void rebuildMissingNameIndex() {
        if (!enabled) {
            return;
        }
        try {
            memberService.rebuildNameIndexAllTenants();
        } catch (RuntimeException failure) {
            log.error("姓名盲索引回填失败（下一轮重试）: cause={}", failure.getMessage(), failure);
        }
    }
}
