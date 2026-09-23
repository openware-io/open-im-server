package io.openware.platform.order.domain.ktv.port;

import io.openware.platform.order.domain.ktv.model.KtvPricingPlan;

/**
 * 计价方案端口：KTV 会话开台/结台时读取门店计价方案。
 * 首发暂无独立 pricing 服务，由 infra 提供可配置默认实现；后续接 pricing 服务替换。
 */
public interface KtvPricingPlanProvider {

    /**
     * 解析租户/门店计价方案。
     *
     * @param tenantId 租户
     * @param storeId  门店，可为空（回退租户级默认）
     */
    KtvPricingPlan resolve(Long tenantId, Long storeId);
}
