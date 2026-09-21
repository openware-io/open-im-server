package com.gvchat.im.accessws.session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@Slf4j
public class SessionRegistry {

  private static final Duration PRESENCE_TTL = Duration.ofSeconds(90);

  private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
  private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();
  private final StringRedisTemplate redisTemplate;

  public SessionRegistry() {
    this.redisTemplate = null;
  }

  @Autowired
  public SessionRegistry(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public void register(Long userId, WebSocketSession session) {
    userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    sessionUsers.put(session.getId(), userId);
    registerPresence(userId, session.getId());
  }

  public void unregister(WebSocketSession session) {
    Long userId = sessionUsers.remove(session.getId());
    if (userId != null) {
      Set<WebSocketSession> sessions = userSessions.get(userId);
      if (sessions != null) {
        sessions.remove(session);
        if (sessions.isEmpty()) {
          userSessions.remove(userId);
        }
      }
      unregisterPresence(userId, session.getId());
    }
  }

  public Set<WebSocketSession> getUserSessions(Long userId) {
    Set<WebSocketSession> sessions = userSessions.get(userId);
    return sessions == null ? Set.of() : Set.copyOf(sessions);
  }

  public int closeUserSessions(Long userId, CloseStatus closeStatus) {
    Set<WebSocketSession> sessions = getUserSessions(userId);
    int closedCount = 0;
    for (WebSocketSession session : sessions) {
      try {
        if (session.isOpen()) {
          session.close(closeStatus);
          closedCount++;
        }
      } catch (Exception exception) {
        log.warn("关闭用户 WebSocket 会话失败, userId={}, sessionId={}", userId, session.getId(), exception);
      } finally {
        unregister(session);
      }
    }
    return closedCount;
  }

  public Set<WebSocketSession> resolveRoomSessions(String room) {
    if (room.startsWith("user:")) {
      try {
        long userId = Long.parseLong(room.substring(5));
        return getUserSessions(userId);
      } catch (NumberFormatException e) {
        log.warn("Failed to resolve websocket room because user room id is invalid, room={}", room, e);
        return Set.of();
      }
    }
    return Set.of();
  }

  public Long getUserId(WebSocketSession session) {
    return sessionUsers.get(session.getId());
  }

  public void refreshPresence(Long userId) {
    if (redisTemplate == null || userId == null || getUserSessions(userId).isEmpty()) return;
    redisTemplate.expire(presenceKey(userId), PRESENCE_TTL);
  }

  public boolean isUserOnline(Long userId) {
    if (userId == null) return false;
    if (redisTemplate == null) return !getUserSessions(userId).isEmpty();
    return Boolean.TRUE.equals(redisTemplate.hasKey(presenceKey(userId)));
  }

  private void registerPresence(long userId, String sessionId) {
    if (redisTemplate == null) return;
    redisTemplate.opsForSet().add(presenceKey(userId), sessionId);
    redisTemplate.expire(presenceKey(userId), PRESENCE_TTL);
  }

  private void unregisterPresence(long userId, String sessionId) {
    if (redisTemplate == null) return;
    String key = presenceKey(userId);
    redisTemplate.opsForSet().remove(key, sessionId);
    Long sessions = redisTemplate.opsForSet().size(key);
    if (sessions == null || sessions == 0) redisTemplate.delete(key);
  }

  private String presenceKey(long userId) {
    return "im:ws:presence:user:" + userId;
  }
}
