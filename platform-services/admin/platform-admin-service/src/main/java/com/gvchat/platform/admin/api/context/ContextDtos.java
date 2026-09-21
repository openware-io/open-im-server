package com.gvchat.platform.admin.api.context;

import java.util.List;

/** 租户上下文选择 BFF DTO（SelectContextResponse 字段名与 X-Tenant-Context 头 JSON 一一对应）。 */
public final class ContextDtos {
    private ContextDtos() {}

    public record ContextItem(String contextId, Long tenantId, String tenantName, Long organizationId,
                              String organizationName, Long storeId, String storeName, List<String> roles,
                              String scopeType) {}

    public record ContextsResponse(List<ContextItem> items) {}

    public record SelectContextRequest(String contextId) {}

    /**
     * 上下文选择结果。
     *
     * <p>{@code currencyCode}（{@code "CNY"|"USD"}）是前端全局 store 的唯一启动来源（规范 §3.2），
     * 与 JWT claim {@code currency} 同源同值：读目标租户配置，缺省 USD。
     */
    public record SelectContextResponse(Long tenantId, Long organizationId, Long storeId, Long accountId,
                                        int authorizationVersion, List<String> permissions, String currencyCode) {}
}
