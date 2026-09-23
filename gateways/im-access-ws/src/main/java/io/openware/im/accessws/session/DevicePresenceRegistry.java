package io.openware.im.accessws.session;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 设备前后台状态注册表：App 通过 WS {@code app:state} 上报前台/后台与当前会话，
 * 供推送决策使用（App 前台 → 只走 WS；后台/心跳超时 → 同时发极光）。
 *
 * <p>状态以 Redis 哈希存储（key {@code im:app:state:user:{userId}}），TTL 90s，
 * 由 App 心跳/任意 WS 帧续期；超时即视为「不在前台」。无 Redis 时（测试）退化为内存回退。</p>
 */
@Component
@Slf4j
public class DevicePresenceRegistry {

  private static final Duration STATE_TTL = Duration.ofSeconds(90);
  private static final String FIELD_APP_STATE = "appState";
  private static final String FIELD_ACTIVE_CONVERSATION = "activeConversationId";
  private static final String FIELD_LAST_HEARTBEAT = "lastHeartbeatAt";

  private final StringRedisTemplate redisTemplate;
  private final Map<Long, AppState> local = new ConcurrentHashMap<>();

  public DevicePresenceRegistry() {
    this.redisTemplate = null;
  }

  @Autowired
  public DevicePresenceRegistry(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** App 上报前后台状态与当前会话。 */
  public void report(long userId, String appState, String activeConversationId) {
    String normalizedState = "foreground".equalsIgnoreCase(appState) ? "foreground" : "background";
    String conversation = activeConversationId == null ? "" : activeConversationId;
    long now = System.currentTimeMillis();
    if (redisTemplate == null) {
      local.put(userId, new AppState(normalizedState, conversation, now));
      return;
    }
    String key = stateKey(userId);
    redisTemplate.opsForHash().putAll(key, Map.of(
        FIELD_APP_STATE, normalizedState,
        FIELD_ACTIVE_CONVERSATION, conversation,
        FIELD_LAST_HEARTBEAT, Long.toString(now)));
    redisTemplate.expire(key, STATE_TTL);
  }

  /** 续期：App 每次发来 WS 帧（含心跳）时调用，保持状态新鲜。 */
  public void touch(long userId) {
    if (redisTemplate == null) {
      return;
    }
    redisTemplate.expire(stateKey(userId), STATE_TTL);
  }

  /** App 是否当前处于前台（状态存在、为 foreground 且心跳未超时）。 */
  public boolean isAppForeground(long userId) {
    if (redisTemplate == null) {
      AppState state = local.get(userId);
      return state != null && "foreground".equals(state.appState);
    }
    Object appState = redisTemplate.opsForHash().get(stateKey(userId), FIELD_APP_STATE);
    return "foreground".equals(appState);
  }

  private String stateKey(long userId) {
    return "im:app:state:user:" + userId;
  }

  private record AppState(String appState, String activeConversationId, long lastHeartbeatAt) {
  }
}
