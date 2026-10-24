package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** B/C 两条 createBooking 入口都必须带事务注解，避免自调用丢失事务。 */
class HotelBookingTransactionTest {

  @Test
  void bothCreateBookingOverloadsAreTransactional() throws NoSuchMethodException {
    Method backend = HotelBookingApplicationService.class.getMethod("createBooking",
        Long.class, Long.class, LocalDate.class, LocalDate.class, int.class,
        String.class, String.class, String.class);
    Method customer = HotelBookingApplicationService.class.getMethod("createBooking",
        Long.class, Long.class, LocalDate.class, LocalDate.class, int.class,
        String.class, String.class, String.class, Long.class);

    assertNotNull(backend.getAnnotation(Transactional.class));
    assertNotNull(customer.getAnnotation(Transactional.class));
  }
}
