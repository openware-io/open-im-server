package com.gvchat.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalServiceAuthenticationTest {
  private static final String SECRET = "test-internal-service-authentication-secret";
  private static final String SOURCE = "im-message-service";
  private static final String ADMIN_BASE_URL = "http://im-admin-service:3400";

  private InternalServiceAuthenticationProperties properties;
  private InternalServiceAuthentication authentication;

  @BeforeEach
  void setUp() {
    properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName(SOURCE);
    properties.setExpectedSource(SOURCE);
    properties.setSecret(SECRET);
    properties.validate();
    authentication = new InternalServiceAuthentication(properties);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldRoundTripSignatureForGetRequest() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, requestId, timestamp);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        requestId, Long.toString(timestamp), authentication.contentHash(body), signature)).isTrue();
  }

  @Test
  void shouldRoundTripSignatureForPostRequestWithQueryAndBody() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/users?page=1");
    byte[] body = "{\"userId\":7}".getBytes(StandardCharsets.UTF_8);
    String contentType = "application/json";
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.POST, uri, contentType, body, SOURCE, requestId,
        timestamp);

    assertThat(authentication.isValid("POST", uri.getRawPath(), uri.getRawQuery(), contentType, body, SOURCE,
        requestId, Long.toString(timestamp), authentication.contentHash(body), signature)).isTrue();
  }

  @Test
  void shouldRejectTamperedSignature() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();
    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, requestId, timestamp);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        requestId, Long.toString(timestamp), authentication.contentHash(body), flipFirstHexChar(signature)))
        .isFalse();
  }

  @Test
  void shouldRejectTamperedBody() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/users");
    byte[] signedBody = "{\"userId\":7}".getBytes(StandardCharsets.UTF_8);
    byte[] tamperedBody = "{\"userId\":8}".getBytes(StandardCharsets.UTF_8);
    String contentType = "application/json";
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.POST, uri, contentType, signedBody, SOURCE,
        requestId, timestamp);

    assertThat(authentication.isValid("POST", uri.getRawPath(), uri.getRawQuery(), contentType, tamperedBody,
        SOURCE, requestId, Long.toString(timestamp), authentication.contentHash(signedBody), signature))
        .isFalse();
  }

  @Test
  void shouldRejectExpiredTimestamp() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long expired = System.currentTimeMillis() - properties.getMaxClockSkewMs() - 1000L;
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, requestId, expired);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        requestId, Long.toString(expired), authentication.contentHash(body), signature)).isFalse();
  }

  @Test
  void shouldRejectFutureTimestamp() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long future = System.currentTimeMillis() + properties.getMaxClockSkewMs() + 1000L;
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, requestId, future);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        requestId, Long.toString(future), authentication.contentHash(body), signature)).isFalse();
  }

  @Test
  void shouldRejectMismatchedSource() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();

    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, requestId, timestamp);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body,
        "im-conversation-service", requestId, Long.toString(timestamp), authentication.contentHash(body), signature))
        .isFalse();
  }

  @Test
  void shouldRejectMalformedRequestId() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long timestamp = System.currentTimeMillis();
    String badRequestId = "too-short";

    String signature = authentication.createSignature(HttpMethod.GET, uri, null, body, SOURCE, badRequestId, timestamp);

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        badRequestId, Long.toString(timestamp), authentication.contentHash(body), signature)).isFalse();
  }

  @Test
  void shouldRejectMalformedSignature() {
    URI uri = URI.create(ADMIN_BASE_URL + "/internal/admin/config/feature-flags");
    byte[] body = new byte[0];
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();

    assertThat(authentication.isValid("GET", uri.getRawPath(), uri.getRawQuery(), null, body, SOURCE,
        requestId, Long.toString(timestamp), authentication.contentHash(body), "malformed-signature")).isFalse();
  }

  @Test
  void shouldRejectReplayedWriteRequest() throws Exception {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    InternalServiceAuthenticationFilter filter =
        new InternalServiceAuthenticationFilter(authentication, redisTemplate);

    MockHttpServletRequest request = signedWriteRequest();
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  private MockHttpServletRequest signedWriteRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/admin/users");
    request.setContentType("application/json");
    request.setContent("{\"userId\":7}".getBytes(StandardCharsets.UTF_8));
    byte[] body = request.getContentAsByteArray();
    long timestamp = System.currentTimeMillis();
    String requestId = requestId();
    request.addHeader(InternalServiceAuthentication.VERSION_HEADER, InternalServiceAuthentication.AUTHENTICATION_VERSION);
    request.addHeader(InternalServiceAuthentication.SOURCE_HEADER, SOURCE);
    request.addHeader(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId);
    request.addHeader(InternalServiceAuthentication.CONTENT_SHA256_HEADER, authentication.contentHash(body));
    request.addHeader(InternalServiceAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp));
    request.addHeader(InternalServiceAuthentication.SIGNATURE_HEADER,
        authentication.createSignature(HttpMethod.POST, URI.create(ADMIN_BASE_URL + "/internal/admin/users"),
            request.getContentType(), body, SOURCE, requestId, timestamp));
    return request;
  }

  private static String requestId() {
    return "a".repeat(32);
  }

  private static String flipFirstHexChar(String hex) {
    char first = hex.charAt(0);
    return (first == '0' ? "1" : "0") + hex.substring(1);
  }
}
