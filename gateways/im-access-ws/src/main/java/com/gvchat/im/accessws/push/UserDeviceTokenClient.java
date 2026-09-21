package com.gvchat.im.accessws.push;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UserDeviceTokenClient {
  private static final String DEFAULT_BASE_URL = "http://im-user-service:3100";
  private static final ParameterizedTypeReference<List<Map<String, String>>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {
      };

  private final RestClient restClient;
  private final String baseUrl;

  public UserDeviceTokenClient(
      InternalServiceAuthenticationInterceptor authenticationInterceptor,
      UserServiceProperties properties) {
    baseUrl = normalizeBaseUrl(properties.getBaseUrl());
    restClient = RestClient.builder().requestInterceptor(authenticationInterceptor).build();
  }

  public List<Map<String, String>> findEnabledTokens(long userId) {
    List<Map<String, String>> tokens = restClient.get()
        .uri(URI.create(baseUrl + "/internal/admin/users/push/" + userId + "/device-tokens"))
        .retrieve().body(RESPONSE_TYPE);
    return tokens == null ? List.of() : tokens;
  }

  /** 查询用户离线推送通知设置（私聊/群聊/频道三类开关）。 */
  public Map<String, Boolean> findNotificationSettings(long userId) {
    Map<String, Boolean> settings = restClient.get()
        .uri(URI.create(baseUrl + "/internal/admin/users/" + userId + "/notification-settings"))
        .retrieve().body(new ParameterizedTypeReference<Map<String, Boolean>>() {});
    return settings == null ? Map.of() : settings;
  }

  private static String normalizeBaseUrl(String configuredBaseUrl) {
    String value = configuredBaseUrl == null || configuredBaseUrl.isBlank()
        ? DEFAULT_BASE_URL : configuredBaseUrl.trim();
    URI uri = URI.create(value);
    if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
      throw new IllegalStateException("Internal user service base URL must be absolute");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
