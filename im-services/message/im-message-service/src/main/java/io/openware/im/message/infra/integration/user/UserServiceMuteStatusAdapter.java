package io.openware.im.message.infra.integration.user;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.im.message.domain.message.port.UserMuteStatusPort;
import io.openware.im.user.api.authorization.UserProfileSummariesQuery;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 用户全局禁言状态适配器：调用用户服务内部接口查询，进程内短 TTL 缓存；
 * 查询失败回退为「未禁言」，绝不阻塞消息发送链路。
 */
@Component
@Slf4j
public class UserServiceMuteStatusAdapter implements UserMuteStatusPort {
  private static final long CACHE_TTL_MS = 60_000L;

  private final RestClient restClient;
  private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

  public UserServiceMuteStatusAdapter(RestClient.Builder builder, UserServiceProperties properties,
      InternalServiceAuthenticationInterceptor interceptor) {
    this.restClient = builder.baseUrl(properties.getBaseUrl()).requestInterceptor(interceptor).build();
  }

  @Override
  public boolean isMuted(long userId) {
    long now = System.currentTimeMillis();
    Entry entry = cache.get(userId);
    if (entry != null && now - entry.fetchedAt() < CACHE_TTL_MS) {
      return entry.muted();
    }
    boolean muted = fetchMuted(userId);
    cache.put(userId, new Entry(muted, now));
    return muted;
  }

  private boolean fetchMuted(long userId) {
    try {
      Map<Long, Boolean> response = restClient.post()
          .uri("/internal/user/authorizations/mute-status")
          .body(new UserProfileSummariesQuery(List.of(userId)))
          .retrieve().body(new ParameterizedTypeReference<Map<Long, Boolean>>() {});
      return response != null && Boolean.TRUE.equals(response.get(userId));
    } catch (RuntimeException ex) {
      log.warn("Failed to fetch mute status, userId={}, assume not muted", userId);
      return false;
    }
  }

  private record Entry(boolean muted, long fetchedAt) {
  }
}
