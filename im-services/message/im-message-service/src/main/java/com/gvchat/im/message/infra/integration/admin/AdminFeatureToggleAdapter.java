package com.gvchat.im.message.infra.integration.admin;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.im.message.domain.message.port.FeatureTogglePort;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 功能开关适配器：调用管理服务内部接口拉取功能开关，带进程内 TTL 缓存；
 * 拉取失败回退到上次缓存（或默认放行），绝不阻塞消息主链路。
 */
@Component
@Slf4j
public class AdminFeatureToggleAdapter implements FeatureTogglePort {
  private static final long CACHE_TTL_MS = 60_000L;

  private final RestClient restClient;
  private volatile Map<String, Boolean> cache = defaultFlags();
  private volatile long lastFetchAt = 0L;

  public AdminFeatureToggleAdapter(RestClient.Builder builder, AdminServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override public boolean isPrivateChatEnabled() { return flag("privateChatEnabled"); }
  @Override public boolean isGroupChatEnabled() { return flag("groupChatEnabled"); }
  @Override public boolean isChannelEnabled() { return flag("channelEnabled"); }
  @Override public boolean isSecretChatEnabled() { return flag("secretChatEnabled"); }
  @Override public boolean isSecretGroupChatEnabled() { return flag("secretGroupChatEnabled"); }
  @Override public boolean isChatDeleteEnabled() { return flag("chatDeleteEnabled"); }

  private boolean flag(String key) {
    long now = System.currentTimeMillis();
    if (now - lastFetchAt >= CACHE_TTL_MS) {
      fetchAndCache();
    }
    return cache.getOrDefault(key, true);
  }

  private void fetchAndCache() {
    try {
      Map<String, Boolean> response = restClient.get()
          .uri("/internal/admin/config/feature-flags")
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Boolean>>() { });
      if (response != null && !response.isEmpty()) {
        cache = response;
        lastFetchAt = System.currentTimeMillis();
      }
    } catch (RuntimeException ex) {
      log.warn("Failed to fetch feature flags, keep last cache");
    }
  }

  private static Map<String, Boolean> defaultFlags() {
    return Map.of("privateChatEnabled", true, "groupChatEnabled", true, "channelEnabled", true,
        "secretChatEnabled", true, "secretGroupChatEnabled", true, "chatDeleteEnabled", true);
  }
}
