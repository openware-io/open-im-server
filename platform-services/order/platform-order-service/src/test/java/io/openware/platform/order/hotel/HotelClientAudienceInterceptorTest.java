package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.infrastructure.tenant.ClientAudienceHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class HotelClientAudienceInterceptorTest {

  private final HotelClientAudienceInterceptor interceptor = new HotelClientAudienceInterceptor();

  @AfterEach
  void clear() {
    ClientAudienceHolder.clear();
  }

  @Test
  void backendAudienceAccepted() throws Exception {
    ClientAudienceHolder.set("saas-b");
    assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
        handler()));
  }

  @Test
  void consumerAudienceRejected() {
    ClientAudienceHolder.set("saas-c");
    assertThrows(ApiException.class, () -> interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), handler()));
  }

  private static HandlerMethod handler() throws NoSuchMethodException {
    return new HandlerMethod(new BackendApi(), BackendApi.class.getMethod("call"));
  }

  @ClientAudience("saas-b")
  static class BackendApi {
    public void call() {
    }
  }
}
