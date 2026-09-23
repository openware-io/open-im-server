package io.openware.common.audit.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
import io.openware.infrastructure.security.InternalServiceSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 内部上报通道鉴权回归：签名/来源/内容哈希三项校验，未通过一律 401，通过后把来源写入请求属性。
 */
class InternalAuditAuthenticationFilterTest {

  private static final String SECRET = "open-im-audit-channel-test-secret-0001";
  private static final String BODY = "{\"action\":\"order.settle\"}";

  private final InternalAuditAuthenticationFilter filter = new InternalAuditAuthenticationFilter(authentication());

  @Test
  void signedRequestFromExpectedSourcePassesAndExposesSourceAttribute() throws Exception {
    MockHttpServletRequest request = signedRequest(BODY, "open-im-audit-reporter");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Object> attribute = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> attribute.set(
        req.getAttribute(InternalAuditAuthenticationFilter.SOURCE_ATTRIBUTE)));

    assertEquals(200, response.getStatus());
    assertEquals("open-im-audit-reporter", attribute.get());
  }

  @Test
  void requestWithoutSignatureIsRejected() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/audit/records");
    request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
        .contains("INVALID_INTERNAL_SERVICE_AUTHENTICATION"));
  }

  @Test
  void tamperedBodyIsRejected() throws Exception {
    MockHttpServletRequest request = signedRequest(BODY, "open-im-audit-reporter");
    request.setContent("{\"action\":\"payment.refund.offline\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(401, response.getStatus());
  }

  @Test
  void unknownSourceIsRejectedEvenWithValidSignature() throws Exception {
    MockHttpServletRequest request = signedRequest(BODY, "platform-order-service");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(401, response.getStatus());
  }

  /** 只有 /internal/** 走签名校验，查询接口不能因为缺少内部签名被拦。 */
  @Test
  void publicQueryPathIsNotFiltered() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    assertTrue(filter.shouldNotFilter(request));
    assertNull(request.getAttribute(InternalAuditAuthenticationFilter.SOURCE_ATTRIBUTE));
  }

  private static MockHttpServletRequest signedRequest(String body, String source) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/audit/records");
    request.setContentType("application/json");
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    request.setContent(payload);
    String requestId = UUID.randomUUID().toString().replace("-", "");
    long timestamp = System.currentTimeMillis();
    String contentHash = InternalServiceSignature.contentHash(payload);
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.SOURCE_HEADER, source);
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.VERSION_HEADER,
        InternalServiceAuthentication.AUTHENTICATION_VERSION);
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.REQUEST_ID_HEADER, requestId);
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.CONTENT_SHA256_HEADER,
        contentHash);
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.TIMESTAMP_HEADER,
        Long.toString(timestamp));
    request.addHeader(io.openware.infrastructure.security.InternalServiceAuthentication.SIGNATURE_HEADER,
        InternalServiceSignature.sign(SECRET, "POST", "/internal/audit/records", null, "application/json",
            contentHash, source, requestId, timestamp));
    return request;
  }

  private static InternalServiceAuthentication authentication() {
    InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName("common-audit-service");
    properties.setExpectedSource("open-im-audit-reporter");
    properties.setSecret(SECRET);
    return new InternalServiceAuthentication(properties);
  }
}
