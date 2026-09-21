package com.gvchat.im.accessws.push;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.net.URI;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 查询 message-service 的服务端权威未读总数（供极光绝对角标）。 */
@Component
public class MessageUnreadClient {
  private static final String DEFAULT_BASE_URL = "http://im-message-service:3200";
  private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {
      };

  private final RestClient restClient;
  private final String baseUrl;

  public MessageUnreadClient(
      InternalServiceAuthenticationInterceptor authenticationInterceptor,
      MessageServiceProperties properties) {
    baseUrl = normalizeBaseUrl(properties.getBaseUrl());
    restClient = RestClient.builder().requestInterceptor(authenticationInterceptor).build();
  }

  public long unreadCount(long userId) {
    Map<String, Object> body = restClient.get()
        .uri(URI.create(baseUrl + "/internal/admin/messages/unread/" + userId))
        .retrieve().body(RESPONSE_TYPE);
    if (body == null || body.get("count") == null) {
      return 0;
    }
    Object count = body.get("count");
    if (count instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(count));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static String normalizeBaseUrl(String configuredBaseUrl) {
    String value = configuredBaseUrl == null || configuredBaseUrl.isBlank()
        ? DEFAULT_BASE_URL : configuredBaseUrl.trim();
    URI uri = URI.create(value);
    if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
      throw new IllegalStateException("Internal message service base URL must be absolute");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
