package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.openware.infrastructure.tenant.ClientAudience;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C 端只读定价/营业时间必须与 saas-b 同时开放，防止 C 预约页再次 403。 */
class ClientAudienceContractTest {

  @Test
  void ktvPricingIsReadableByConsumer() {
    ClientAudience audience = KtvPricingController.class.getAnnotation(ClientAudience.class);
    assertEquals(Set.of("saas-b", "saas-c"), Set.copyOf(Arrays.asList(audience.value())));
  }

  @Test
  void businessHoursIsReadableByConsumer() throws NoSuchMethodException {
    ClientAudience audience = ReservationController.class
        .getMethod("businessHours", Long.class)
        .getAnnotation(ClientAudience.class);
    assertEquals(Set.of("saas-b", "saas-c"), Set.copyOf(Arrays.asList(audience.value())));
  }
}
