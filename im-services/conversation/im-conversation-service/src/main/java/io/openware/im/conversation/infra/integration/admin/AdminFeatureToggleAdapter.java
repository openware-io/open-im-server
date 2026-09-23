package io.openware.im.conversation.infra.integration.admin;

import io.openware.im.conversation.domain.group.port.FeatureTogglePort;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 群聊开关适配器：调用管理服务内部接口，带 60s TTL 缓存；拉取失败默认放行（true）。 */
@Component
@Slf4j
public class AdminFeatureToggleAdapter implements FeatureTogglePort {
  private static final long CACHE_TTL_MS = 60_000L;

  private final RestClient restClient;
  private volatile boolean groupChatEnabled = true;
  private volatile long lastFetchAt = 0L;

  public AdminFeatureToggleAdapter(AdminServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public boolean isGroupChatEnabled() {
    long now = System.currentTimeMillis();
    if (now - lastFetchAt >= CACHE_TTL_MS) {
      fetchAndCache();
    }
    return groupChatEnabled;
  }

  private void fetchAndCache() {
    try {
      Map<String, Boolean> response = restClient.get()
          .uri("/internal/admin/config/feature-flags")
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Boolean>>() { });
      if (response != null && response.containsKey("groupChatEnabled")) {
        groupChatEnabled = Boolean.TRUE.equals(response.get("groupChatEnabled"));
        lastFetchAt = System.currentTimeMillis();
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to fetch feature flags, keep last cache (groupChatEnabled={})", groupChatEnabled);
    }
  }
}
