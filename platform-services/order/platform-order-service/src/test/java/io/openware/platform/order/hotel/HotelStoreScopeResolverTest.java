package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HotelStoreScopeResolverTest {

  private final StoreScopeRepository stores = mock(StoreScopeRepository.class);
  private final HotelStoreScopeResolver resolver = new HotelStoreScopeResolver(stores);

  @Test
  void readAllowsActiveHotelStore() {
    when(stores.findActive(100L, 102L)).thenReturn(Optional.of(
        new StoreScopeRepository.StoreScope(100L, 102L, 100L, "HOTEL", "ACTIVE")));

    var scope = resolver.requireHotelReadScope(context(102L));

    assertEquals("HOTEL", scope.businessType());
  }

  @Test
  void readRejectsKtvStore() {
    when(stores.findActive(100L, 100L)).thenReturn(Optional.of(
        new StoreScopeRepository.StoreScope(100L, 100L, 100L, "KTV", "ACTIVE")));

    ApiException error = assertThrows(ApiException.class,
        () -> resolver.requireHotelReadScope(context(100L)));

    assertEquals(409, error.getStatus());
    assertEquals("STORE_BUSINESS_TYPE_MISMATCH", error.getCode());
  }

  @Test
  void readRejectsMissingStoreContext() {
    ApiException error = assertThrows(ApiException.class,
        () -> resolver.requireHotelReadScope(context(null)));

    assertEquals(422, error.getStatus());
    assertEquals("STORE_SCOPE_REQUIRED", error.getCode());
  }

  private static TenantContext context(Long storeId) {
    return new TenantContext(100L, 100L, storeId, 6L, 1, List.of("hotel.booking.view"),
        "STORE", "HOTEL", "Asia/Shanghai");
  }
}
