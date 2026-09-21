package com.gvchat.platform.identity.api.dto;

import java.util.List;

/** 经营上下文选择（SAAS_PLATFORM_05 §3.1）DTO。 */
public final class AuthContextDtos {
    private AuthContextDtos() {}

    public record ContextItem(String contextId, Long tenantId, String tenantName, Long organizationId,
                              String organizationName, Long storeId, String storeName, List<String> roles,
                              String scopeType) {}

    public record ContextsResponse(List<ContextItem> items) {}

    public record SelectContextRequest(String contextId) {}

    /**
     * 上下文选择结果。
     *
     * <p>{@code currencyCode}（{@code "CNY"|"USD"}）是 C 端/客户端全局 store 的唯一启动来源（规范 §3.2），
     * 与 JWT claim {@code currency} 同源同值：读目标租户配置，缺省 USD。
     */
    public record SelectContextResponse(String tenantContextToken, long expiresAt, Long tenantId, Long organizationId,
                                        Long storeId, int authorizationVersion, List<String> permissions,
                                        List<String> allowedActions, String currencyCode) {}
}
