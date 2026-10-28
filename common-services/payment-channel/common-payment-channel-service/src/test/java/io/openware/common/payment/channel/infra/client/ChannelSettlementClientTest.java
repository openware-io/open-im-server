package io.openware.common.payment.channel.infra.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceSignature;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 渠道结算内部客户端单测：用 JDK HttpServer 起真实 loopback，校验 v2 canonical HMAC 的 6 个 headers、
 * 请求体、以及非 2xx/缺密钥时的失败语义（必须抛异常让调用方让渠道重试，不能静默成功）。
 */
class ChannelSettlementClientTest {

  private static final String SECRET = "test-only-internal-service-secret-32-chars";
  private static final String SERVICE = "common-payment-channel-service";

  private HttpServer server;
  private volatile CapturedRequest captured;
  private volatile int responseStatus;
  private volatile String responseBody;
  private int port;

  @BeforeEach
  void startServer() throws IOException {
    responseStatus = 200;
    responseBody = "{}";
    captured = null;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(ChannelSettlementClient.SETTLEMENT_PATH, exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      captured = new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestHeaders(), body);
      byte[] response = responseBody.getBytes(UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(responseStatus, response.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(response);
      }
    });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void signsRequestWithV2CanonicalHmacHeadersAndSendsPayload() {
    responseBody = "{\"provider\":\"ALIPAY\",\"transactionId\":\"ALI-TX-1\",\"orderId\":9001,"
        + "\"channelStatus\":\"SUCCEEDED\",\"settlementType\":\"PAYMENT\",\"applied\":true,"
        + "\"replayed\":false,\"payIntentId\":11,\"payTransactionId\":12,\"tradeStatus\":\"PAID\","
        + "\"refundStatus\":\"NONE\",\"paidAmount\":8000,\"currencyCode\":\"CNY\",\"settlementAmount\":8000}";

    ChannelSettlementResponse response = client().settle(request("ALI-TX-1", 8000L));

    assertTrue(response.applied());
    assertEquals("PAID", response.tradeStatus());
    assertEquals(new BigDecimal("8000"), response.paidAmount());

    CapturedRequest actual = captured;
    assertEquals("POST", actual.method());
    assertEquals(InternalServiceAuthentication.AUTHENTICATION_VERSION,
        header(actual.headers(), InternalServiceAuthentication.VERSION_HEADER));
    assertEquals(SERVICE, header(actual.headers(), InternalServiceAuthentication.SOURCE_HEADER));
    assertEquals("application/json", header(actual.headers(), "Content-Type"));

    String contentHash = header(actual.headers(), InternalServiceAuthentication.CONTENT_SHA256_HEADER);
    assertEquals(InternalServiceSignature.contentHash(actual.body()), contentHash);

    String requestId = header(actual.headers(), InternalServiceAuthentication.REQUEST_ID_HEADER);
    assertTrue(requestId.matches("[A-Za-z0-9_-]{22,128}"), requestId);
    String timestamp = header(actual.headers(), InternalServiceAuthentication.TIMESTAMP_HEADER);
    String signature = header(actual.headers(), InternalServiceAuthentication.SIGNATURE_HEADER);
    String expected = InternalServiceSignature.sign(SECRET, "POST", ChannelSettlementClient.SETTLEMENT_PATH,
        null, "application/json", contentHash, SERVICE, requestId, Long.parseLong(timestamp));
    assertEquals(expected, signature, "签名必须与服务端 v2 canonical 口径一致");

    String payload = new String(actual.body(), UTF_8);
    assertTrue(payload.contains("\"transactionId\":\"ALI-TX-1\""), payload);
    assertTrue(payload.contains("\"amountMinor\":8000"), payload);
    assertTrue(payload.contains("\"status\":\"SUCCEEDED\""), payload);
  }

  @Test
  void non2xxResponseBecomesRetryableFailure() {
    responseStatus = 422;
    responseBody = "{\"code\":\"PAYMENT_AMOUNT_MISMATCH\",\"message\":\"金额不一致\"}";

    ChannelSettlementException error = assertThrows(ChannelSettlementException.class,
        () -> client().settle(request("ALI-TX-2", 8000L)));

    assertTrue(error.getMessage().contains("status=422"), error.getMessage());
  }

  @Test
  void missingSecretFailsFastWithoutSendingRequest() {
    ChannelSettlementClient client = new ChannelSettlementClient(RestClient.builder()
        .baseUrl("http://127.0.0.1:" + port).build(), SERVICE, "  ");

    ChannelSettlementException error = assertThrows(ChannelSettlementException.class,
        () -> client.settle(request("ALI-TX-3", 8000L)));

    assertTrue(error.getMessage().contains("internal.service-auth"), error.getMessage());
    assertNull(captured, "缺密钥时不得发出任何请求");
  }

  private ChannelSettlementClient client() {
    return new ChannelSettlementClient(
        RestClient.builder().baseUrl("http://127.0.0.1:" + port).build(), SERVICE, SECRET);
  }

  private static ChannelSettlementRequest request(String transactionId, long amountMinor) {
    return new ChannelSettlementRequest("alipay", transactionId, "notify-1", 1001L, null, "9001",
        amountMinor, "CNY", "SUCCEEDED", null);
  }

  private static String header(Headers headers, String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private record CapturedRequest(String method, Headers headers, byte[] body) {
  }
}
