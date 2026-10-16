package io.openware.platform.tenant.api.dto;

/**
 * 门店响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code StorePo}，使对外契约与表映射耦合。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy/At、updatedBy/At）。
 */
public record StoreView(
    Long id,
    Long tenantId,
    Long organizationId,
    String code,
    String name,
    String businessType,
    String countryCode,
    String regionCode,
    String timezone,
    String defaultCurrency,
    String locale,
    Long taxProfileId,
    String businessDayCutoff,
    String status) {
}
