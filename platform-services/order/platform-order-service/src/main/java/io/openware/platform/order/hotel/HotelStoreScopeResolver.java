package io.openware.platform.order.hotel;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import org.springframework.stereotype.Component;

/**
 * 酒店写路径的门店权威作用域。
 *
 * <p>organization_id / business_type 只能来自门店权威数据（tnt_store），不能用 storeId 兜底；
 * 门店缺失、停用、缺组织或业态不是酒店时拒绝写入，避免把酒店数据写进错的组织。
 */
@Component
public class HotelStoreScopeResolver {

    public static final String BUSINESS_TYPE_HOTEL = "HOTEL";

    private final StoreScopeRepository storeScopeRepository;

    public HotelStoreScopeResolver(StoreScopeRepository storeScopeRepository) {
        this.storeScopeRepository = storeScopeRepository;
    }

    public StoreScopeRepository.StoreScope requireHotelScope(TenantContext context) {
        if (context == null || context.storeId() == null || context.tenantId() <= 0) {
            throw new ApiException(422, "STORE_SCOPE_REQUIRED", "缺少门店上下文，不能写入酒店领域数据");
        }
        StoreScopeRepository.StoreScope scope = storeScopeRepository
                .findActive(context.tenantId(), context.storeId())
                .orElseThrow(() -> new ApiException(422, "STORE_SCOPE_MISSING",
                        "门店不存在或已停用，不能写入酒店领域数据"));
        if (scope.organizationId() == null) {
            throw new ApiException(422, "STORE_ORGANIZATION_MISSING", "门店缺少组织归属，不能写入酒店领域数据");
        }
        if (scope.businessType() == null
                || !BUSINESS_TYPE_HOTEL.equalsIgnoreCase(scope.businessType().trim())) {
            throw new ApiException(409, "STORE_BUSINESS_TYPE_MISMATCH", "门店业态不是酒店，不能写入酒店领域数据");
        }
        return scope;
    }
}
