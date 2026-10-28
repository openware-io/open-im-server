package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.PricingPlanApplicationService;
import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.domain.pricing.repository.PricingPlanRepository;
import io.openware.platform.tenant.domain.store.model.Store;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 批量计价方案的**先整批校验、再逐店写入**：门店归属/存在/业态一致三项判定必须在任何写入之前完成，
 * 否则前半批落库、后半批失败会留下半截配置。
 *
 * <p>替身是领域端口；门店事实经 {@code StoreRepository}，因此测试不再构造 MyBatis mapper 的返回形状。
 */
class InternalPricingPlanBatchTest {
  private final PricingPlanRepository pricing = mock(PricingPlanRepository.class);
  private final StoreRepository stores = mock(StoreRepository.class);
  private final PricingPlanApplicationService service = new PricingPlanApplicationService(pricing, stores);
  private final InternalPricingPlanController controller = new InternalPricingPlanController(service);

  @AfterEach
  void clear() { TenantContextHolder.clear(); }

  @Test
  void rejectsCrossTenantBatchBeforeWrite() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.findOwnerTenantId(10L)).thenReturn(Optional.of(200L));
    var plan = new InternalPricingPlanController.CreatePricingRequest(100L, null, "KTV", "KTV_ROOM",
        "HOUR", 30, "ROUND_UP", 1000L, 120, null, null, "batch-1");
    ApiException error = assertThrows(ApiException.class, () -> controller.createBatch(
        new InternalPricingPlanController.BatchPricingRequest(plan, List.of(10L), "batch-1")));
    assertEquals("STORE_SCOPE_FORBIDDEN", error.getCode());
    verify(pricing, never()).insert(any(PricingPlan.class), any());
  }

  @Test
  void rejectsMixedBusinessTypeBatchBeforeWrite() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.findOwnerTenantId(10L)).thenReturn(Optional.of(100L));
    when(stores.findOwnerTenantId(11L)).thenReturn(Optional.of(100L));
    when(stores.findById(10L)).thenReturn(Optional.of(store(10L, "KTV")));
    when(stores.findById(11L)).thenReturn(Optional.of(store(11L, "RETAIL")));
    var plan = new InternalPricingPlanController.CreatePricingRequest(100L, null, null, "KTV_ROOM",
        "HOUR", 30, "ROUND_UP", 1000L, 120, null, null, "batch-2");
    ApiException error = assertThrows(ApiException.class, () -> controller.createBatch(
        new InternalPricingPlanController.BatchPricingRequest(plan, List.of(10L, 11L), "batch-2")));
    assertEquals("BUSINESS_TYPE_MISMATCH", error.getCode());
    // 第一个门店已校验通过，但整批失败时仍不得有任何写入
    verify(pricing, never()).insert(any(PricingPlan.class), any());
  }

  @Test
  void validatesStoreScopeContract() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.findOwnerTenantId(10L)).thenReturn(Optional.of(100L));
    when(stores.findById(10L)).thenReturn(Optional.of(store(10L, "KTV")));
    var result = controller.validateStoreScope(
        new InternalPricingPlanController.StoreScopeRequest(null, List.of(10L)));
    assertEquals("KTV", result.businessType());
    assertEquals(10L, result.stores().get(0).storeId());
  }

  private static Store store(long id, String businessType) {
    return new Store(id, 100L, 1L, "S-" + id, "store-" + id, businessType, "CN", null, "Asia/Shanghai",
        "CNY", "zh-CN", null, "04:00:00", Store.STATUS_ACTIVE, null);
  }
}
