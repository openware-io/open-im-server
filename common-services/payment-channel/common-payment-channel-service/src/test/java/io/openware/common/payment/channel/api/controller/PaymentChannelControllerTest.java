package io.openware.common.payment.channel.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.channel.application.ChannelCallbackSettlementBridge;
import io.openware.common.payment.channel.application.ChannelProviderApplicationService;
import io.openware.common.payment.channel.spi.CallbackAck;
import io.openware.common.payment.channel.spi.ChannelCapability;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 回调入口 ack 语义单测：结算成功才回渠道成功 ack；验签通过但结算失败必须回失败 ack（让渠道重投）；
 * 验签失败不改资金事实、也不会调用结算桥。
 */
class PaymentChannelControllerTest {

  private final ChannelProviderApplicationService channelService = mock(ChannelProviderApplicationService.class);
  private final ChannelCallbackSettlementBridge settlementBridge = mock(ChannelCallbackSettlementBridge.class);
  private final PaymentChannelController controller =
      new PaymentChannelController(channelService, settlementBridge);

  private static final VerifyCallbackResult VERIFIED = new VerifyCallbackResult(true, "ALI-TX-1", "9001",
      8000L, "CNY", "SUCCEEDED", "raw-body", "notify-1", 1001L);
  private static final VerifyCallbackResult UNVERIFIED = new VerifyCallbackResult(false, null, null, 0L,
      null, "FAILED", "raw-body", null, null);

  @Test
  void verifiedAndSettledReturnsChannelSuccessAck() {
    when(channelService.verifyCallback(eq("alipay"), any())).thenReturn(VERIFIED);
    when(settlementBridge.settle("alipay", VERIFIED)).thenReturn(true);
    when(channelService.callbackAck("alipay", VERIFIED))
        .thenReturn(CallbackAck.ok("text/plain;charset=utf-8", "success"));

    ResponseEntity<String> response = controller.callback("alipay", "raw-body", Map.of());

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("success", response.getBody());
    verify(channelService).callbackAck("alipay", VERIFIED);
    verify(channelService, never()).retryAck(any(), any());
  }

  @Test
  void verifiedButSettlementFailedReturnsRetryAck() {
    when(channelService.verifyCallback(eq("wechat"), any())).thenReturn(VERIFIED);
    when(settlementBridge.settle("wechat", VERIFIED)).thenReturn(false);
    when(channelService.retryAck("wechat", VERIFIED))
        .thenReturn(CallbackAck.fail("application/xml;charset=utf-8", "<xml>FAIL</xml>"));

    ResponseEntity<String> response = controller.callback("wechat", "raw-body", Map.of());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("<xml>FAIL</xml>", response.getBody());
    verify(channelService).retryAck("wechat", VERIFIED);
    verify(channelService, never()).callbackAck(any(), any());
  }

  @Test
  void unverifiedCallbackSkipsSettlement() {
    when(channelService.verifyCallback(eq("stripe"), any())).thenReturn(UNVERIFIED);
    when(channelService.callbackAck("stripe", UNVERIFIED))
        .thenReturn(CallbackAck.fail("text/plain;charset=utf-8", "fail"));

    ResponseEntity<String> response = controller.callback("stripe", "raw-body", Map.of());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verify(settlementBridge, never()).settle(any(), any());
    verify(channelService).callbackAck("stripe", UNVERIFIED);
  }

  @Test
  void availableIsDelegated() {
    when(channelService.available()).thenReturn(java.util.List.of(new ChannelCapability("alipay", true)));

    assertEquals(1, controller.available().size());
  }
}
