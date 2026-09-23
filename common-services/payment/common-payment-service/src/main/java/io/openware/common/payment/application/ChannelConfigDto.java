package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.po.ChannelConfigPo;

import java.time.LocalDateTime;

/** 支付渠道配置响应 DTO。 */
public record ChannelConfigDto(Long id, Long tenantId, Long storeId, String channel, Integer enabled,
                               String merchantId, String status, Long createdBy, LocalDateTime createdAt,
                               Long updatedBy, LocalDateTime updatedAt) {
    public static ChannelConfigDto from(ChannelConfigPo po) {
        return new ChannelConfigDto(po.getId(), po.getTenantId(), po.getStoreId(), po.getChannel(), po.getEnabled(),
                po.getMerchantId(), po.getStatus(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    }
}
