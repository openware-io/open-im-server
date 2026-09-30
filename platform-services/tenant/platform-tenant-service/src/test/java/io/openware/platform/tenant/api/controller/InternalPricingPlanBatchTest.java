package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.PricingPlanMapper;
import io.openware.platform.tenant.infra.persistence.mapper.StoreMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class InternalPricingPlanBatchTest {
  private final PricingPlanMapper pricing = Mockito.mock(PricingPlanMapper.class);
  private final StoreMapper stores = Mockito.mock(StoreMapper.class);
  private final InternalPricingPlanController controller = new InternalPricingPlanController(pricing, stores);

  @AfterEach
  void clear() { TenantContextHolder.clear(); }

  @Test
  void rejectsCrossTenantBatchBeforeWrite() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.selectTenantIdById(10L)).thenReturn(200L);
    var plan = new InternalPricingPlanController.CreatePricingRequest(100L, null, "KTV", "KTV_ROOM",
        "HOUR", 30, "ROUND_UP", 1000L, 120, null, null, "batch-1");
    ApiException error = assertThrows(ApiException.class, () -> controller.createBatch(
        new InternalPricingPlanController.BatchPricingRequest(plan, List.of(10L), "batch-1")));
    assertEquals("STORE_SCOPE_FORBIDDEN", error.getCode());
  }

  @Test
  void rejectsMixedBusinessTypeBatchBeforeWrite() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.selectTenantIdById(10L)).thenReturn(100L);
    when(stores.selectTenantIdById(11L)).thenReturn(100L);
    when(stores.selectBusinessTypeById(10L)).thenReturn("KTV");
    when(stores.selectBusinessTypeById(11L)).thenReturn("RETAIL");
    var plan = new InternalPricingPlanController.CreatePricingRequest(100L, null, null, "KTV_ROOM",
        "HOUR", 30, "ROUND_UP", 1000L, 120, null, null, "batch-2");
    ApiException error = assertThrows(ApiException.class, () -> controller.createBatch(
        new InternalPricingPlanController.BatchPricingRequest(plan, List.of(10L, 11L), "batch-2")));
    assertEquals("BUSINESS_TYPE_MISMATCH", error.getCode());
  }

  @Test
  void validatesStoreScopeContract() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1));
    when(stores.selectTenantIdById(10L)).thenReturn(100L);
    when(stores.selectBusinessTypeById(10L)).thenReturn("KTV");
    var result = controller.validateStoreScope(new InternalPricingPlanController.StoreScopeRequest(null, List.of(10L)));
    assertEquals("KTV", result.businessType());
    assertEquals(10L, result.stores().get(0).storeId());
  }
}
