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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class InternalServiceAuthenticationFilterTest {
  private InternalServiceAuthentication authentication;
  private InternalServiceAuthenticationFilter filter;
  private StringRedisTemplate redisTemplate;
  @Mock
  private ValueOperations<String, String> valueOperations;

  @BeforeEach
  void setUp() {
    InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName("im-user-service");
    properties.setExpectedSource("im-admin-service");
    properties.setSecret("test-internal-service-authentication-secret");
    properties.validate();
    authentication = new InternalServiceAuthentication(properties);
    redisTemplate = mock(StringRedisTemplate.class);
    filter = new InternalServiceAuthenticationFilter(authentication, redisTemplate);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldAuthenticateValidAdminServiceRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/internal/admin/users");
    request.setQueryString("page=1");
    long timestamp = System.currentTimeMillis();
    sign(request, URI.create("/internal/admin/users?page=1"), timestamp);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_INTERNAL_ADMIN");
  }

  @Test
  void shouldAuthenticateSignedIamRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(),
        "/internal/iam/consumer/saas-a380-h5/context");
    request.setQueryString("accountId=158");
    sign(request, URI.create("/internal/iam/consumer/saas-a380-h5/context?accountId=158"),
        System.currentTimeMillis());

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_INTERNAL_ADMIN");
  }

  @Test
  void shouldRejectAnonymousIamRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(),
        "/internal/iam/account/158/contexts");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute("internal_service_auth_error"))
        .isEqualTo("INVALID_INTERNAL_SERVICE_AUTHENTICATION");
  }

  @Test
  void shouldRejectAnonymousInternalAdminRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/internal/admin/users");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute("internal_service_auth_error"))
        .isEqualTo("INVALID_INTERNAL_SERVICE_AUTHENTICATION");
  }

  @Test
  void shouldRejectRequestFromUnexpectedService() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/internal/admin/users");
    long timestamp = System.currentTimeMillis();
    sign(request, URI.create("/internal/admin/users"), timestamp);
    request.removeHeader(InternalServiceAuthentication.SOURCE_HEADER);
    request.addHeader(InternalServiceAuthentication.SOURCE_HEADER, "im-message-service");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldRejectRequestWithInvalidSignature() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), "/internal/admin/users");
    request.addHeader(InternalServiceAuthentication.VERSION_HEADER, InternalServiceAuthentication.AUTHENTICATION_VERSION);
    request.addHeader(InternalServiceAuthentication.SOURCE_HEADER, "im-admin-service");
    request.addHeader(InternalServiceAuthentication.REQUEST_ID_HEADER, "a".repeat(32));
    request.addHeader(InternalServiceAuthentication.CONTENT_SHA256_HEADER, authentication.contentHash(new byte[0]));
    request.addHeader(InternalServiceAuthentication.TIMESTAMP_HEADER, System.currentTimeMillis());
    request.addHeader(InternalServiceAuthentication.SIGNATURE_HEADER, "0".repeat(64));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldRejectTamperedWriteRequestBody() throws Exception {
    MockHttpServletRequest request = writeRequest();
    sign(request, URI.create("/internal/user/authorizations/private-message"), System.currentTimeMillis());
    request.setContent("{\"userId\":8}".getBytes(StandardCharsets.UTF_8));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldRejectReplayedWriteRequest() throws Exception {
    MockHttpServletRequest request = writeRequest();
    sign(request, URI.create("/internal/user/authorizations/private-message"), System.currentTimeMillis());
    mockReplayOperations();
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldRejectWriteRequestWhenReplayDefenseIsUnavailable() throws Exception {
    MockHttpServletRequest request = writeRequest();
    sign(request, URI.create("/internal/user/authorizations/private-message"), System.currentTimeMillis());
    mockReplayOperations();
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("Redis unavailable"));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldAuthenticateSignedWriteRequestOnlyOnce() throws Exception {
    MockHttpServletRequest request = writeRequest();
    sign(request, URI.create("/internal/user/authorizations/private-message"), System.currentTimeMillis());
    mockReplayOperations();
    when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  private MockHttpServletRequest writeRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), "/internal/user/authorizations/private-message");
    request.setContentType("application/json");
    request.setContent("{\"userId\":7}".getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private void mockReplayOperations() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  private void sign(MockHttpServletRequest request, URI uri, long timestamp) {
    byte[] body = request.getContentAsByteArray();
    if (body == null) {
      body = new byte[0];
    }
    String requestId = "a".repeat(32);
    request.addHeader(InternalServiceAuthentication.VERSION_HEADER, InternalServiceAuthentication.AUTHENTICATION_VERSION);
    request.addHeader(InternalServiceAuthentication.SOURCE_HEADER, "im-admin-service");
    request.addHeader(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId);
    request.addHeader(InternalServiceAuthentication.CONTENT_SHA256_HEADER, authentication.contentHash(body));
    request.addHeader(InternalServiceAuthentication.TIMESTAMP_HEADER, timestamp);
    request.addHeader(InternalServiceAuthentication.SIGNATURE_HEADER,
        authentication.createSignature(HttpMethod.valueOf(request.getMethod()), uri, request.getContentType(), body, "im-admin-service",
            requestId, timestamp));
  }
}
