package com.gvchat.im.accessws.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.accessws.broadcast.RedisClusterBroadcast;
import com.gvchat.im.accessws.util.WsRoomUtil;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
@Service
@Slf4j
public class WsBroadcastService {

  private final RedisClusterBroadcast redisClusterBroadcast;
  private final ObjectMapper objectMapper;

  public WsBroadcastService(RedisClusterBroadcast redisClusterBroadcast, ObjectMapper objectMapper) {
    this.redisClusterBroadcast = redisClusterBroadcast;
    this.objectMapper = objectMapper;
  }

  public boolean sendToUser(long userId, String event, Map<String, Object> data) {
    return publishToUserRoom(WsRoomUtil.userRoom(userId), event, data);
  }

  public void sendToSession(WebSocketSession session, String event, Map<String, Object> data) {
    try {
      Map<String, Object> frame = new HashMap<>();
      frame.put("event", event);
      if (data != null) {
        frame.put("data", data);
      }
      session.sendMessage(new TextMessage(requireNonNull(objectMapper.writeValueAsString(frame))));
    } catch (Exception ex) {
      log.error(
          "Failed to send websocket frame to session, sessionId={}, event={}",
          session.getId(),
          event,
          ex);
    }
  }

  private boolean publishToUserRoom(String room, String event, Map<String, Object> data) {
    try {
      Map<String, Object> pub = new HashMap<>();
      pub.put("room", room);
      pub.put("event", event);
      pub.put("data", data);
      redisClusterBroadcast.publish(pub);
      return true;
    } catch (Exception ex) {
      log.error(
          "Failed to publish websocket user event, room={}, event={}",
          room,
          event,
          ex);
      return false;
    }
  }
}
