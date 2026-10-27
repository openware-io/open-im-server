package io.openware.common.payment.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.ChannelSettlementApplicationService;
import io.openware.common.payment.application.ChannelSettlementCommand;
import io.openware.common.payment.application.ChannelSettlementResult;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 渠道结算内部接口的鉴权门：filter 未认证时必须在进入应用服务之前 401。 */
class InternalChannelSettlementControllerTest {

  private final ChannelSettlementApplicationService settlementService =
      Mockito.mock(ChannelSettlementApplicationService.class);
  private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
  private final InternalChannelSettlementController controller =
      new InternalChannelSettlementController(settlementService, request);

  private static final ChannelSettlementCommand COMMAND = new ChannelSettlementCommand("alipay",
      "ALI-TX-1", null, 100L, 200L, "9001", 8000L, "CNY", "SUCCEEDED", null);

  @Test
  void rejectsMissingInternalAuthentication() {
    ApiException error = assertThrows(ApiException.class, () -> controller.settle(COMMAND));

    assertEquals(401, error.getStatus());
    assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", error.getCode());
    verify(settlementService, never()).settle(Mockito.any());
  }

  @Test
  void delegatesSettlementWhenInternalAuthenticationPassed() {
    when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(Boolean.TRUE);
    ChannelSettlementResult expected = new ChannelSettlementResult("ALIPAY", "ALI-TX-1", null, 9001L,
        "SUCCEEDED", "PAYMENT", true, false, 1L, 2L, "PAID", "NONE", new BigDecimal("8000"), "CNY",
        new BigDecimal("8000"));
    when(settlementService.settle(COMMAND)).thenReturn(expected);

    ChannelSettlementResult actual = controller.settle(COMMAND);

    assertEquals(expected, actual);
    verify(settlementService).settle(COMMAND);
  }
}
