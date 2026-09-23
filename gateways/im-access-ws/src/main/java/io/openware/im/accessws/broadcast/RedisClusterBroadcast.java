package io.openware.im.accessws.broadcast;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.accessws.session.SessionRegistry;
import io.openware.common.constant.RedisKeys;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 基于 Redis Pub/Sub 的 WebSocket 集群广播组件。
 * 接入层先把房间事件发布到 Redis，再由各节点把消息推送给本地会话。
 */
@Component
@Slf4j
public class RedisClusterBroadcast implements MessageListener {
  private static final String AUTHENTICATION_INVALIDATED_EVENT = "authentication_invalidated";
  private static final String DATA_WIPE_REQUESTED_EVENT = "data_wipe_requested";
  private final StringRedisTemplate redisTemplate;
  private final RedisMessageListenerContainer listenerContainer;
  private final SessionRegistry sessionRegistry;
  private final ObjectMapper objectMapper;

  public RedisClusterBroadcast(
      StringRedisTemplate redisTemplate,
      RedisMessageListenerContainer listenerContainer,
      SessionRegistry sessionRegistry,
      ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
    this.sessionRegistry = sessionRegistry;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void subscribe() {
    listenerContainer.addMessageListener(this, new ChannelTopic(requireNonNull(RedisKeys.WS_BROADCAST)));
    log.info("Subscribed redis websocket broadcast channel, channel={}", RedisKeys.WS_BROADCAST);
  }

  public void publish(Map<String, Object> payload) throws Exception {
    redisTemplate.convertAndSend(
        requireNonNull(RedisKeys.WS_BROADCAST),
        requireNonNull(objectMapper.writeValueAsString(payload)));
    log.debug(
        "Published websocket broadcast payload, room={}, event={}, excludeSessionId={}",
        payload.get("room"),
        payload.get("event"),
        payload.get("excludeSessionId"));
  }

  public void publishAuthenticationInvalidation(Long userId) throws Exception {
    publish(Map.of(
        "room", "user:" + userId,
        "event", AUTHENTICATION_INVALIDATED_EVENT,
        "data", Map.of("userId", userId)));
  }

  public void publishDataWipe(Long userId) throws Exception {
    publish(Map.of(
        "room", "user:" + userId,
        "event", DATA_WIPE_REQUESTED_EVENT,
        "data", Map.of("userId", userId)));
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = objectMapper.readValue(message.getBody(), Map.class);
      String room = String.valueOf(payload.get("room"));
      String event = String.valueOf(payload.get("event"));
      @SuppressWarnings("unchecked")
      Map<String, Object> data = payload.get("data") instanceof Map
          ? (Map<String, Object>) payload.get("data") : Map.of();
      String excludeSessionId = payload.get("excludeSessionId") != null
          ? String.valueOf(payload.get("excludeSessionId")) : null;

      if (AUTHENTICATION_INVALIDATED_EVENT.equals(event)) {
        Long userId = Long.parseLong(room.substring("user:".length()));
        int closedCount = sessionRegistry.closeUserSessions(userId, CloseStatus.POLICY_VIOLATION);
        log.info("已通过集群广播关闭认证失效用户会话, userId={}, closedCount={}", userId, closedCount);
        return;
      }

      if (DATA_WIPE_REQUESTED_EVENT.equals(event)) {
        Long userId = Long.parseLong(room.substring("user:".length()));
        Map<String, Object> frame = new HashMap<>();
        frame.put("event", event);
        frame.put("data", data);
        String text = objectMapper.writeValueAsString(frame);
        for (WebSocketSession session : sessionRegistry.getUserSessions(userId)) {
          if (session.isOpen()) {
            session.sendMessage(new TextMessage(requireNonNull(text)));
          }
        }
        int closedCount = sessionRegistry.closeUserSessions(userId, CloseStatus.POLICY_VIOLATION);
        log.info("已通过集群广播通知用户擦除本地数据并关闭会话, userId={}, closedCount={}", userId, closedCount);
        return;
      }

      Map<String, Object> frame = new HashMap<>();
      frame.put("event", event);
      frame.put("data", data);
      String text = objectMapper.writeValueAsString(frame);

      Set<WebSocketSession> targets = sessionRegistry.resolveRoomSessions(room);
      for (WebSocketSession session : targets) {
        if (excludeSessionId != null && excludeSessionId.equals(session.getId())) {
          continue;
        }
        if (session.isOpen()) {
          session.sendMessage(new TextMessage(requireNonNull(text)));
        }
      }
      log.debug(
          "Delivered redis websocket broadcast locally, room={}, event={}, targetSessionCount={}",
          room,
          event,
          targets.size());
    } catch (Exception ex) {
      log.error("Failed to process redis websocket broadcast message.", ex);
    }
  }
}
