package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.po.ChannelConfigPo;

import java.time.LocalDateTime;

/** 支付渠道配置响应 DTO。 */
public record ChannelConfigDto(Long id, Long tenantId, Long storeId, String businessType, String channel, Integer enabled,
                               String merchantId, String status, Integer version, String idempotencyKey,
                               Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    public static ChannelConfigDto from(ChannelConfigPo po) {
        return new ChannelConfigDto(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(), po.getChannel(), po.getEnabled(),
                po.getMerchantId(), po.getStatus(), po.getVersion(), po.getIdempotencyKey(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    }
}
