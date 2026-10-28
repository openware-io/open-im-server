package io.openware.common.payment.channel.infra.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.channel.infra.config.PayChannelProviderProperties;
import io.openware.common.payment.channel.infra.crypto.PaymentCryptoUtils;
import io.openware.common.payment.channel.infra.persistence.PaymentChannelTransactionRecorder;
import io.openware.common.payment.channel.spi.CallbackAck;
import io.openware.common.payment.channel.spi.VerifyCallbackRequest;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SANDBOX 渠道单测：HMAC 验签成功/失败、字段解析，以及验签失败绝不落渠道交易。 */
class SandboxChannelTest {

  private static final String SECRET = "local-e2e-sandbox-webhook-secret";
  private static final String BODY = "{\"transactionId\":\"E2E-SANDBOX-TX-1\",\"eventId\":\"E2E-SANDBOX-EVT-1\","
      + "\"orderId\":\"9001\",\"amount\":8000,\"currency\":\"CNY\",\"status\":\"SUCCEEDED\",\"tenantId\":100}";

  private final PayChannelProviderProperties properties = new PayChannelProviderProperties();
  private final PaymentChannelTransactionRecorder recorder = mock(PaymentChannelTransactionRecorder.class);
  private final SandboxChannel channel = new SandboxChannel(properties, recorder);

  @Test
  void disabledSandboxRejectsCallbackAndWritesNothing() {
    properties.getSandbox().setEnabled(false);
    properties.getSandbox().setWebhookSecret(SECRET);

    VerifyCallbackResult result = channel.verifyCallback(request(BODY, signature(BODY)));

    assertFalse(result.verified());
    verify(recorder, never()).record(any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void validHmacSignatureVerifiesAndRecordsChannelTransaction() {
    enable();
    when(recorder.existsByEventId("sandbox", "E2E-SANDBOX-EVT-1")).thenReturn(false);

    VerifyCallbackResult result = channel.verifyCallback(request(BODY, signature(BODY)));

    assertTrue(result.verified());
    assertEquals("E2E-SANDBOX-TX-1", result.providerTransactionNo());
    assertEquals("9001", result.orderId());
    assertEquals(8000L, result.amount());
    assertEquals("CNY", result.currency());
    assertEquals("SUCCEEDED", result.status());
    assertEquals("E2E-SANDBOX-EVT-1", result.eventId());
    assertEquals(100L, result.tenantId());
    verify(recorder).record(eq("sandbox"), eq("E2E-SANDBOX-TX-1"), eq("E2E-SANDBOX-EVT-1"), eq(100L),
        eq("9001"), eq(8000L), eq("CNY"), eq("SUCCEEDED"), eq(BODY));
  }

  @Test
  void duplicateEventIdDoesNotRecordTwice() {
    enable();
    when(recorder.existsByEventId("sandbox", "E2E-SANDBOX-EVT-1")).thenReturn(true);

    assertTrue(channel.verifyCallback(request(BODY, signature(BODY))).verified());

    verify(recorder, never()).record(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyString());
  }

  @Test
  void wrongSignatureIsRejectedWithoutTouchingFunds() {
    enable();

    VerifyCallbackResult result = channel.verifyCallback(request(BODY, signature(BODY + "tampered")));

    assertFalse(result.verified());
    assertNull(result.providerTransactionNo());
    verify(recorder, never()).record(any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void missingSignatureHeaderIsRejected() {
    enable();

    assertFalse(channel.verifyCallback(request(BODY, null)).verified());
  }

  @Test
  void callbackAckMapsVerifiedToSuccessJson() {
    enable();

    CallbackAck ok = channel.callbackAck(channel.verifyCallback(request(BODY, signature(BODY))));
    CallbackAck fail = channel.callbackAck(new VerifyCallbackResult(false, null, null, 0L, null, "FAILED",
        BODY, null, null));

    assertEquals(200, ok.httpStatus());
    assertTrue(ok.body().contains("SUCCESS"));
    assertEquals(400, fail.httpStatus());
  }

  private void enable() {
    properties.getSandbox().setEnabled(true);
    properties.getSandbox().setWebhookSecret(SECRET);
  }

  private static String signature(String body) {
    return PaymentCryptoUtils.hmacSha256Hex(body, SECRET);
  }

  private static VerifyCallbackRequest request(String body, String signature) {
    Map<String, String> headers = signature == null ? Map.of() : Map.of(SandboxChannel.SIGNATURE_HEADER, signature);
    return new VerifyCallbackRequest(body, headers);
  }
}
