package io.openware.platform.order.hotel.channel;

import io.openware.infrastructure.tenant.TenantContext;
import java.util.List;

/**
 * OTA 适配器在服务端构造的租户上下文。
 *
 * <p>外部请求不携带 {@code X-Tenant-Context}，也不允许携带 organization/business：租户来自渠道凭证，
 * 门店/组织来自 {@code htl_hotel.store_id} 与 {@code tnt_store} 权威数据。这里只声明适配器本身需要的
 * 两个业务权限码（酒店预订查询/创建），不是「无权限上下文」，也不存在绕过 {@code PermissionGuard} 的分支。
 */
public final class HotelChannelContexts {

    /** 适配器在服务端持有的最小权限集合；门店与组织仍由 HotelStoreScopeResolver 复核。 */
    public static final List<String> ADAPTER_PERMISSIONS =
            List.of("hotel.booking.view", "hotel.booking.create");

    private HotelChannelContexts() {
    }

    /** 鉴权通过后的临时上下文：只锁定租户，门店/组织尚未解析。 */
    public static TenantContext provisional(long tenantId) {
        return new TenantContext(tenantId, null, null, 0L, 0, ADAPTER_PERMISSIONS, null, null, null);
    }

    /** 门店权威数据解析后的上下文：organization/businessType 只来自 {@code tnt_store}。 */
    public static TenantContext scoped(long tenantId, Long organizationId, Long storeId, String businessType) {
        return new TenantContext(tenantId, organizationId, storeId, 0L, 0, ADAPTER_PERMISSIONS,
                null, businessType, null);
    }
}
