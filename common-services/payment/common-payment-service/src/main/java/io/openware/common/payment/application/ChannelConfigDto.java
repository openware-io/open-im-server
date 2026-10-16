package io.openware.common.payment.application;

import io.openware.common.payment.domain.channel.model.ChannelConfig;

import java.time.LocalDateTime;

/**
 * 支付渠道配置响应 DTO。
 *
 * <p>由领域模型构造，**不暴露记账字段**（createdBy、updatedBy）：它们是审计事实，
 * 由审计留痕承载，不该和业务配置混在同一个响应里（standards/10 的 D2 规则）。
 * {@code createdAt}/{@code updatedAt} 保留——运营需要知道这条渠道开关是什么时候改的。
 */
public record ChannelConfigDto(Long id, Long tenantId, Long storeId, String businessType, String channel,
                               Boolean enabled, String merchantId, String status, Integer version,
                               String idempotencyKey, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 由领域模型构造；入参为空时返回空。 */
    public static ChannelConfigDto from(ChannelConfig config) {
        if (config == null) {
            return null;
        }
        return new ChannelConfigDto(config.id(), config.tenantId(), config.storeId(), config.businessType(),
                config.channel(), config.enabled(), config.merchantId(), config.status(), config.version(),
                config.idempotencyKey(), config.createdAt(), config.updatedAt());
    }
}
