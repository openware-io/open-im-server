package io.openware.im.message.infra.integration.admin;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.moderation.ModerationWordRule;
import io.openware.im.message.domain.moderation.ModerationWordSource;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 审核词来源适配器：调用管理服务内部接口拉取已启用审核词，带进程内 TTL 缓存；
 * 拉取失败回退到上次缓存（或空，即不做过滤），绝不阻塞消息发送链路。
 */
@Component
@Slf4j
public class AdminModerationWordSource implements ModerationWordSource {
  private static final long CACHE_TTL_MS = 60_000L;

  private final RestClient restClient;
  private volatile List<ModerationWordRule> cache = List.of();
  private volatile long lastFetchAt = 0L;

  public AdminModerationWordSource(RestClient.Builder builder, AdminServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public List<ModerationWordRule> enabledWords() {
    long now = System.currentTimeMillis();
    if (now - lastFetchAt >= CACHE_TTL_MS) {
      fetchAndCache();
    }
    return cache;
  }

  private void fetchAndCache() {
    try {
      List<Map<String, String>> response = restClient.get()
          .uri("/internal/admin/sensitive-words/enabled")
          .retrieve()
          .body(new ParameterizedTypeReference<List<Map<String, String>>>() {});
      cache = response == null ? List.of() : response.stream()
          .filter(item -> item != null && item.get("word") != null && item.get("level") != null)
          .map(item -> new ModerationWordRule(item.get("word"), item.get("level")))
          .toList();
      lastFetchAt = System.currentTimeMillis();
    } catch (RuntimeException ex) {
      log.warn("Failed to fetch enabled moderation words, keep last cache ({} words)", cache.size());
    }
  }
}
