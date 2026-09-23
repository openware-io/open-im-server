package io.openware.im.accessws.push;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 查询 conversation-service 的用户会话免打扰状态（供离线推送过滤）。 */
@Component
@Slf4j
public class ConversationMuteClient {
  private static final String DEFAULT_BASE_URL = "http://im-conversation-service:3300";
  private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {
      };

  private final RestClient restClient;
  private final String baseUrl;

  public ConversationMuteClient(
      InternalServiceAuthenticationInterceptor authenticationInterceptor,
      ConversationServiceProperties properties) {
    baseUrl = normalizeBaseUrl(properties.getBaseUrl());
    restClient = RestClient.builder().requestInterceptor(authenticationInterceptor).build();
  }

  /** 会话是否被该用户免打扰；查询失败时保守返回 false（fail-open，不影响消息到达）。 */
  public boolean isMuted(long userId, String conversationId) {
    try {
      Map<String, Object> body = restClient.get()
          .uri(URI.create(baseUrl + "/internal/admin/conversations/muted?userId=" + userId
              + "&conversationId=" + conversationId))
          .retrieve().body(RESPONSE_TYPE);
      return body != null && Boolean.TRUE.equals(body.get("muted"));
    } catch (RuntimeException ex) {
      log.warn("Failed to query conversation mute, fail-open, userId={}, conversationId={}", userId, conversationId, ex);
      return false;
    }
  }

  private static String normalizeBaseUrl(String configuredBaseUrl) {
    String value = configuredBaseUrl == null || configuredBaseUrl.isBlank()
        ? DEFAULT_BASE_URL : configuredBaseUrl.trim();
    URI uri = URI.create(value);
    if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
      throw new IllegalStateException("Internal conversation service base URL must be absolute");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
