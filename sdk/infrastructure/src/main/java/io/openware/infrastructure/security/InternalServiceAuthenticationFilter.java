package io.openware.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import io.openware.common.constant.RedisKeys;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

@Component
@Slf4j
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {
  private static final String INTERNAL_ADMIN_PATH = "/internal/admin/";
  private static final String INTERNAL_CONVERSATION_PATH = "/internal/conversation/";
  private static final String INTERNAL_USER_PATH = "/internal/user/";
  private static final String INTERNAL_MEDIA_PATH = "/internal/media/";
  private static final String INTERNAL_CHANNELS_PATH = "/internal/channels/";
  private static final String INTERNAL_SECRET_CHATS_PATH = "/internal/secret-chats/";
  private static final String INTERNAL_SECRET_GROUP_CHATS_PATH = "/internal/secret-group-chats/";
  private static final String INTERNAL_IAM_PATH = "/internal/iam/";
  private static final String INTERNAL_CUSTOMER_PATH = "/internal/customer/";
  private static final String INTERNAL_PAYMENT_PATH = "/internal/payment/";
  private static final String INTERNAL_TENANT_PATH = "/internal/tenant/";
  private static final String INTERNAL_ORDER_PATH = "/internal/order/";

  /** Request attribute set only after a v2 internal-service signature has been accepted. */
  public static final String AUTHENTICATED_ATTRIBUTE =
      InternalServiceAuthenticationFilter.class.getName() + ".authenticated";

  private final InternalServiceAuthentication authentication;
  private final StringRedisTemplate stringRedisTemplate;

  public InternalServiceAuthenticationFilter(InternalServiceAuthentication authentication,
      StringRedisTemplate stringRedisTemplate) {
    this.authentication = authentication;
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith(INTERNAL_ADMIN_PATH)
        || request.getRequestURI().startsWith(INTERNAL_CONVERSATION_PATH)
        || request.getRequestURI().startsWith(INTERNAL_USER_PATH)
        || request.getRequestURI().startsWith(INTERNAL_MEDIA_PATH)
        || request.getRequestURI().startsWith(INTERNAL_CHANNELS_PATH)
        || request.getRequestURI().startsWith(INTERNAL_SECRET_CHATS_PATH)
        || request.getRequestURI().startsWith(INTERNAL_SECRET_GROUP_CHATS_PATH)
        || request.getRequestURI().startsWith(INTERNAL_IAM_PATH)
        || request.getRequestURI().startsWith(INTERNAL_CUSTOMER_PATH)
        || request.getRequestURI().startsWith(INTERNAL_PAYMENT_PATH)
        || request.getRequestURI().startsWith(INTERNAL_TENANT_PATH)
        || request.getRequestURI().startsWith(INTERNAL_ORDER_PATH)) {
      String source = request.getHeader(InternalServiceAuthentication.SOURCE_HEADER);
      CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
      byte[] body = cachedRequest.getCachedBody();
      boolean signatureValid = isValid(cachedRequest, source, body);
      boolean replayAccepted = signatureValid && (!requiresReplayDefense(cachedRequest)
          || registerRequest(source, cachedRequest.getHeader(InternalServiceAuthentication.REQUEST_ID_HEADER)));
      if (replayAccepted) {
        cachedRequest.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        var token = new UsernamePasswordAuthenticationToken(
            source, null, AuthorityUtils.createAuthorityList("ROLE_INTERNAL_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(token);
      } else {
        log.warn("Rejected internal service request: method={}, path={}, source={}, signatureValid={}, replayAccepted={}",
            cachedRequest.getMethod(), cachedRequest.getRequestURI(), source, signatureValid, replayAccepted);
        cachedRequest.setAttribute("internal_service_auth_error", "INVALID_INTERNAL_SERVICE_AUTHENTICATION");
      }
      chain.doFilter(cachedRequest, response);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean isValid(HttpServletRequest request, String source, byte[] body) {
    return InternalServiceAuthentication.AUTHENTICATION_VERSION.equals(
        request.getHeader(InternalServiceAuthentication.VERSION_HEADER))
        && isExpectedSource(source)
        && authentication.isValid(
            request.getMethod(), request.getRequestURI(), request.getQueryString(), request.getContentType(), body,
            source, request.getHeader(InternalServiceAuthentication.REQUEST_ID_HEADER),
            request.getHeader(InternalServiceAuthentication.TIMESTAMP_HEADER),
            request.getHeader(InternalServiceAuthentication.CONTENT_SHA256_HEADER),
            request.getHeader(InternalServiceAuthentication.SIGNATURE_HEADER));
  }

  private boolean registerRequest(String source, String requestId) {
    try {
      return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
          RedisKeys.INTERNAL_AUTH_REQUEST + source + ":" + requestId,
          "1",
          Duration.ofMillis(authentication.getRequestIdTtlMs())));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean requiresReplayDefense(HttpServletRequest request) {
    return !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
        && !"OPTIONS".equals(request.getMethod());
  }

  private boolean isExpectedSource(String source) {
    if (source == null) return false;
    return java.util.Arrays.stream(authentication.getExpectedSource().split(","))
        .map(String::trim).anyMatch(source::equals);
  }
}
