package io.openware.im.admin.integration;

import io.openware.common.dto.PageResult;
import io.openware.im.admin.integration.InternalServiceProperties;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 开放平台第三方接入内部管理客户端（HMAC）：调用 im-user-service /internal/admin/open-applications。
 * 批准/重置密钥返回一次性明文 appSecret，直接透传给管理端展示。
 */
@Component
@RequiredArgsConstructor
public class OpenPlatformAdminClient {
  private static final ParameterizedTypeReference<PageResult<Map<String, Object>>> PAGE =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<Map<String, Object>> ITEM =
      new ParameterizedTypeReference<>() {};

  private final RestClient.Builder restClientBuilder;
  private final InternalServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(properties.getBaseUrl())
        .requestInterceptor(authenticationInterceptor).build();
  }

  public PageResult<Map<String, Object>> listApplications(String status, String appType, int page, int pageSize) {
    return client().get().uri(builder -> builder.path("/internal/admin/open-applications")
        .queryParam("page", page).queryParam("pageSize", pageSize)
        .queryParamIfPresent("status", Optional.ofNullable(status))
        .queryParamIfPresent("appType", Optional.ofNullable(appType)).build()).retrieve().body(PAGE);
  }

  public Map<String, Object> detail(String appId) {
    return client().get().uri("/internal/admin/open-applications/{appId}", appId).retrieve().body(ITEM);
  }

  public Map<String, Object> approve(String appId) {
    return client().post().uri("/internal/admin/open-applications/{appId}/approve", appId)
        .retrieve().body(ITEM);
  }

  public Map<String, Object> reject(String appId, String reason) {
    return client().post().uri("/internal/admin/open-applications/{appId}/reject", appId)
        .body(Map.of("reason", reason == null ? "" : reason)).retrieve().body(ITEM);
  }

  public Map<String, Object> resetSecret(String appId) {
    return client().post().uri("/internal/admin/open-applications/{appId}/reset-secret", appId)
        .retrieve().body(ITEM);
  }

  public void revoke(String appId) {
    client().post().uri("/internal/admin/open-applications/{appId}/revoke", appId)
        .retrieve().toBodilessEntity();
  }
}
