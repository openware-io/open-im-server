package com.gvchat.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计上报客户端契约回归：
 * 上下文自动补全、详情脱敏、关闭态不触网，以及**出站签名能被服务端 InternalServiceAuthentication 验证通过**
 * （历史上出站占位签名与服务端 HMAC 口径不一致，内部接口一开强校验就会全部 401）。
 */
class AuditClientTest {

  private static final String SECRET = "gv-im-audit-channel-test-secret-0001";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @AfterEach
  void cleanup() {
    TenantContextHolder.clear();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void fillsTenantOperatorAndRequestMetadataFromContext() {
    TenantContextHolder.set(new TenantContext(1001L, 20L, 30L, 77L, 3, List.of("order.settle")));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "10.0.0.9, 10.0.0.1");
    request.addHeader("User-Agent", "gv-test-agent");
    request.addHeader("X-Request-Id", "req-123");
    request.addHeader("X-Trace-Id", "trace-123");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    AuditClient client = new AuditClient(RestClient.builder().build(), SECRET);

    Map<String, Object> body = client.buildBody(AuditClient.AuditRecord.of(null, null, "order.settle",
        "ord_order", "9", null, null, "{\"password\":\"p@ss\",\"phone\":\"13800001111\"}"));

    assertEquals(1001L, body.get("tenantId"));
    assertEquals(20L, body.get("organizationId"));
    assertEquals(30L, body.get("storeId"));
    assertEquals(77L, body.get("operatorId"));
    assertEquals("10.0.0.9", body.get("ip"));
    assertEquals("gv-test-agent", body.get("userAgent"));
    assertEquals("req-123", body.get("requestId"));
    assertEquals("trace-123", body.get("traceId"));
    assertEquals("结台结算", body.get("actionLabel"));
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, body.get("result"));
    // 详情按隐私口径脱敏：密码整体抹除、手机号掩码。
    String detail = String.valueOf(body.get("detailJson"));
    assertFalse(detail.contains("p@ss"));
    assertTrue(detail.contains("[REDACTED]"));
    assertTrue(detail.contains("138****11"));
  }

  @Test
  void keepsExplicitValuesAndDefaultsTenantToPlatformLevel() {
    AuditClient client = new AuditClient(RestClient.builder().build(), SECRET);
    Map<String, Object> body = client.buildBody(AuditClient.AuditRecord.builder()
        .action("tenant.create").tenantId(0L).operatorType(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM)
        .result(AuditClient.AuditRecord.RESULT_FAILURE).errorCode("TENANT_CODE_DUPLICATED")
        .detailJson("not-json-text").build());

    assertEquals(0L, body.get("tenantId"));
    assertNull(body.get("operatorId"));
    assertEquals(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM, body.get("operatorType"));
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, body.get("result"));
    // 非 JSON 详情按纯文本包装，保证审计表 json 列永远拿到合法 JSON。
    assertEquals("\"not-json-text\"", body.get("detailJson"));
  }

  /**
   * ② 平台作用域上下文：平台级动作（无租户约束）未显式声明 operatorType 时也自动记 PLATFORM，
   * 并按上下文补全 operatorId —— 平台运营创建租户只带 selected tenant，不再丢操作人。
   */
  @Test
  void platformScopedContextFillsOperatorAndPlatformOperatorType() {
    TenantContextHolder.set(new TenantContext(0L, null, null, 900L, 0, List.of(),
        TenantContext.SCOPE_PLATFORM));
    AuditClient client = new AuditClient(RestClient.builder().build(), SECRET);

    Map<String, Object> body = client.buildBody(AuditClient.AuditRecord.builder()
        .tenantId(1234L).action("tenant.create").resourceType("tnt_tenant").resourceId("1234").build());

    assertEquals(900L, body.get("operatorId"));
    assertEquals(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM, body.get("operatorType"));
    assertEquals(1234L, body.get("tenantId"));
  }

  /** 关键回归：客户端签名头必须能通过服务端同一实现的校验（内容哈希含 body，改一个字节即失效）。 */
  @Test
  void outboundSignatureIsAcceptedByServerSideVerification() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://audit.local");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    AuditClient client = new AuditClient(builder.build(), SECRET);
    AuditClient.AuditRecord record = AuditClient.AuditRecord.builder()
        .tenantId(1001L).operatorId(77L).action("payment.collect").resourceType("collect")
        .resourceId("C-1").detailJson("{\"amount\":\"10.00\"}").build();
    byte[] expectedBody = MAPPER.writeValueAsBytes(client.buildBody(record));
    AtomicReference<ClientHttpRequest> captured = new AtomicReference<>();
    server.expect(requestTo("http://audit.local/internal/audit/records"))
        .andExpect(request -> captured.set(request))
        .andRespond(withSuccess("{\"id\":7,\"duplicated\":false}", MediaType.APPLICATION_JSON));

    assertEquals(7L, client.record(record));

    InternalServiceAuthentication serverAuthentication = serverAuthentication();
    ClientHttpRequest sent = captured.get();
    Assertions.assertNotNull(sent);
    assertTrue(serverAuthentication.isValid("POST", "/internal/audit/records", null, "application/json",
        expectedBody, AuditClient.DEFAULT_REPORTER_SOURCE,
        sent.getHeaders().getFirst(InternalServiceAuthentication.REQUEST_ID_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.TIMESTAMP_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.CONTENT_SHA256_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.SIGNATURE_HEADER)),
        "审计上报的 HMAC 签名必须能被服务端验签通过");
    assertFalse(serverAuthentication.isValid("POST", "/internal/audit/records", null, "application/json",
        "{\"tampered\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        AuditClient.DEFAULT_REPORTER_SOURCE,
        sent.getHeaders().getFirst(InternalServiceAuthentication.REQUEST_ID_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.TIMESTAMP_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.CONTENT_SHA256_HEADER),
        sent.getHeaders().getFirst(InternalServiceAuthentication.SIGNATURE_HEADER)),
        "请求体被篡改后必须验签失败");
  }

  @Test
  void disabledClientNeverTouchesNetwork() {
    AuditClient client = AuditClient.disabled();
    assertNull(client.record(AuditClient.AuditRecord.of(1L, 1L, "order.settle", "ord_order", "1", null, null, null)));
    assertEquals(0, client.recordBatch(List.of(AuditClient.AuditRecord.of(1L, 1L, "order.settle",
        "ord_order", "1", null, null, null))));
    client.recordAsync(AuditClient.AuditRecord.of(1L, 1L, "order.settle", "ord_order", "1", null, null, null))
        .join();
  }

  @Test
  void batchOverLimitIsRejectedLoudly() {
    List<AuditClient.AuditRecord> records = java.util.stream.IntStream
        .range(0, AuditClient.MAX_BATCH_SIZE + 1)
        .mapToObj(index -> AuditClient.AuditRecord.of(1L, 1L, "order.settle", "ord_order",
            String.valueOf(index), null, null, null))
        .toList();
    AuditClient client = new AuditClient(RestClient.builder().baseUrl("http://unused.local").build(), SECRET);
    // 超限必须在发起 HTTP 之前就被拒绝（宁可让调用方拆分，也不静默截断丢失审计）。
    Assertions.assertThrows(IllegalArgumentException.class, () -> client.recordBatch(records));
  }

  private static InternalServiceAuthentication serverAuthentication() {
    InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName("common-audit-service");
    properties.setExpectedSource(AuditClient.DEFAULT_REPORTER_SOURCE);
    properties.setSecret(SECRET);
    return new InternalServiceAuthentication(properties);
  }
}
