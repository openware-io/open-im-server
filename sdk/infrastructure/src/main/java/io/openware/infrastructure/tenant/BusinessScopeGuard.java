package io.openware.infrastructure.tenant;

import io.openware.common.exception.ApiException;

/**
 * 业态作用域守卫：门店上下文必须属于指定业务类型。
 *
 * <p>同一个 {@code store.manager} 角色可能同时负责酒店、KTV 或后续按摩/桌球门店，
 * 只有 storeId + 角色权限还不够；KTV 配置/预约、酒店前台等接口还需要校验当前门店的
 * {@code businessType}。这样酒店店长即使拥有通用店长角色，也不能读取 KTV 业务数据。
 */
public final class BusinessScopeGuard {
    private BusinessScopeGuard() {}

    public static void require(String expectedBusinessType) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.businessType() == null
                || !expectedBusinessType.equalsIgnoreCase(context.businessType())) {
            throw new ApiException(403, "BUSINESS_SCOPE_FORBIDDEN", "当前门店不支持该业务操作");
        }
    }
}
