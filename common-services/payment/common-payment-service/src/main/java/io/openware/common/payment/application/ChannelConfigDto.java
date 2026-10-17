package io.openware.common.payment.application;

import io.openware.common.payment.domain.channel.model.ChannelConfig;
import io.openware.common.payment.domain.channel.repository.Persisted;

import java.time.LocalDateTime;

/**
 * 支付渠道配置响应 DTO。
 *
 * <p>由「聚合 + 记账快照」构造，**不暴露记账字段**（createdBy、updatedBy）：它们是审计事实，
 * 由审计留痕承载。{@code createdAt}/{@code updatedAt} 保留——运营需要知道这条渠道开关是什么时候改的，
 * 值从端口返回的 {@code Persisted.audit()} 取，而不是放回领域模型。
 */
public record ChannelConfigDto(Long id, Long tenantId, Long storeId, String businessType, String channel,
                               Boolean enabled, String merchantId, String status, Integer version,
                               String idempotencyKey, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 由「聚合 + 记账快照」构造；入参为空时返回空。 */
    public static ChannelConfigDto from(Persisted<ChannelConfig> persisted) {
        if (persisted == null || persisted.aggregate() == null) {
            return null;
        }
        ChannelConfig config = persisted.aggregate();
        return new ChannelConfigDto(config.id(), config.tenantId(), config.storeId(), config.businessType(),
                config.channel(), config.enabled(), config.merchantId(), config.status(), config.version(),
                config.idempotencyKey(),
                persisted.audit() == null ? null : persisted.audit().createdAt(),
                persisted.audit() == null ? null : persisted.audit().updatedAt());
    }
}
