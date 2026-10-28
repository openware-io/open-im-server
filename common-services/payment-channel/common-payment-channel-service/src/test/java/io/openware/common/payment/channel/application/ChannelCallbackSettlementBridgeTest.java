package io.openware.common.payment.channel.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.channel.infra.client.ChannelSettlementClient;
import io.openware.common.payment.channel.infra.client.ChannelSettlementException;
import io.openware.common.payment.channel.infra.client.ChannelSettlementRequest;
import io.openware.common.payment.channel.infra.client.ChannelSettlementResponse;
import io.openware.common.payment.channel.infra.persistence.PaymentChannelTransactionRecorder;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 结算桥单测：验签成功后的字段映射、成功落结算状态、失败必须返回 false（让渠道重试）并记录可重试状态。
 */
class ChannelCallbackSettlementBridgeTest {

  private final ChannelSettlementClient client = mock(ChannelSettlementClient.class);
  private final PaymentChannelTransactionRecorder recorder = mock(PaymentChannelTransactionRecorder.class);
  private final ChannelCallbackSettlementBridge bridge = new ChannelCallbackSettlementBridge(client, recorder);

  private static final VerifyCallbackResult VERIFIED = new VerifyCallbackResult(true, "ALI-TX-1", "9001",
      8000L, "CNY", "SUCCEEDED", "raw-body", "notify-1", 1001L);

  @Test
  void settleSuccessMarksAppliedAndMapsVerifyResultToInternalRequest() {
    when(client.settle(any())).thenReturn(new ChannelSettlementResponse("ALIPAY", "ALI-TX-1", "notify-1",
        9001L, "SUCCEEDED", "PAYMENT", true, false, 11L, 12L, "PAID", "NONE",
        new BigDecimal("8000"), "CNY", new BigDecimal("8000")));

    assertTrue(bridge.settle("alipay", VERIFIED));

    ArgumentCaptor<ChannelSettlementRequest> request = ArgumentCaptor.forClass(ChannelSettlementRequest.class);
    verify(client).settle(request.capture());
    assertEquals("alipay", request.getValue().provider());
    assertEquals("ALI-TX-1", request.getValue().transactionId());
    assertEquals("notify-1", request.getValue().eventId());
    assertEquals(1001L, request.getValue().tenantId());
    assertNull(request.getValue().storeId());
    assertEquals("9001", request.getValue().orderId());
    assertEquals(8000L, request.getValue().amountMinor());
    assertEquals("CNY", request.getValue().currency());
    assertEquals("SUCCEEDED", request.getValue().status());
    verify(recorder).markSettlement("alipay", "ALI-TX-1",
        ChannelCallbackSettlementBridge.SETTLEMENT_APPLIED, null);
  }

  @Test
  void settleFailureReturnsFalseAndPersistsRetryableState() {
    when(client.settle(any())).thenThrow(new ChannelSettlementException("payment-service 不可达"));

    assertFalse(bridge.settle("wechat", VERIFIED));

    verify(recorder).markSettlement("wechat", "ALI-TX-1",
        ChannelCallbackSettlementBridge.SETTLEMENT_FAILED, "payment-service 不可达");
  }

  @Test
  void settleFailureStillReturnsFalseWhenRetryStateCannotBePersisted() {
    when(client.settle(any())).thenThrow(new ChannelSettlementException("payment-service 返回非 2xx: status=500"));
    doThrow(new IllegalStateException("db down")).when(recorder)
        .markSettlement(eq("stripe"), eq("ALI-TX-1"), eq(ChannelCallbackSettlementBridge.SETTLEMENT_FAILED), any());

    // 结算状态落库失败不得把"结算失败"变成"结算成功"：仍必须让渠道重试。
    assertFalse(bridge.settle("stripe", VERIFIED));
  }
}
