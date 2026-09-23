package io.openware.im.accessws.handler;

import lombok.extern.slf4j.Slf4j;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.accessws.message.MessageAcceptance;
import io.openware.im.accessws.message.MqMessageGateway;
import io.openware.im.accessws.push.JPushMessageService;
import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.im.accessws.session.DevicePresenceRegistry;
import io.openware.im.accessws.session.SessionRegistry;
import io.openware.protocol.ws.constant.WsEvents;
import io.openware.protocol.ws.dto.ReadReceiptDto;
import io.openware.protocol.ws.dto.RecallMessageDto;
import io.openware.protocol.ws.dto.SendMessageDto;
import io.openware.protocol.ws.dto.WsRtcSignalDto;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@Slf4j
public class ImWebSocketHandler extends TextWebSocketHandler {
  private final SessionRegistry sessionRegistry;
  private final DevicePresenceRegistry devicePresenceRegistry;
  private final MqMessageGateway mqMessageGateway;
  private final WsBroadcastService wsBroadcastService;
  private final JPushMessageService jpushMessageService;
  private final ObjectMapper objectMapper;

  public ImWebSocketHandler(
      SessionRegistry sessionRegistry,
      DevicePresenceRegistry devicePresenceRegistry,
      MqMessageGateway mqMessageGateway,
      WsBroadcastService wsBroadcastService,
      JPushMessageService jpushMessageService,
      ObjectMapper objectMapper) {
    this.sessionRegistry = sessionRegistry;
    this.devicePresenceRegistry = devicePresenceRegistry;
    this.mqMessageGateway = mqMessageGateway;
    this.wsBroadcastService = wsBroadcastService;
    this.jpushMessageService = jpushMessageService;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Long userId = (Long) session.getAttributes().get("userId");
    if (userId == null) {
      log.warn("拒绝未认证用户建立 WebSocket 连接, sessionId={}", session.getId());
      session.close(requireNonNull(CloseStatus.NOT_ACCEPTABLE));
      return;
    }

    sessionRegistry.register(userId, session);
    sessionRegistry.refreshPresence(userId);
    log.info("WebSocket 连接建立成功, userId={}, sessionId={}", userId, session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Long userId = sessionRegistry.getUserId(session);
    sessionRegistry.unregister(session);
    if (userId == null) {
      log.debug("WebSocket 会话关闭时未绑定用户, sessionId={}", session.getId());
      return;
    }

    log.info("WebSocket 连接已关闭, userId={}, sessionId={}, status={}", userId, session.getId(), status);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    Long userId = sessionRegistry.getUserId(session);
    if (userId == null) {
      log.warn("忽略未知会话的 WebSocket 帧, sessionId={}", session.getId());
      return;
    }

    String username = (String) session.getAttributes().getOrDefault("username", "");
    sessionRegistry.refreshPresence(userId);
    devicePresenceRegistry.touch(userId);
    String event = "unknown";
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> frame = objectMapper.readValue(message.getPayload(), Map.class);
      event = String.valueOf(frame.get("event"));
      @SuppressWarnings("unchecked")
      Map<String, Object> data = frame.get("data") instanceof Map ? (Map<String, Object>) frame.get("data") : Map.of();
      log.debug("收到 WebSocket 事件, userId={}, sessionId={}, event={}", userId, session.getId(), event);

      switch (event) {
        case WsEvents.HEARTBEAT -> wsBroadcastService.sendToSession(
            session, WsEvents.HEARTBEAT, Map.of("timestamp", System.currentTimeMillis()));
        case WsEvents.APP_STATE -> handleAppState(userId, data);
        case WsEvents.CHAT_SEND -> handleChatSend(session, userId, username, data);
        case WsEvents.CHAT_READ -> handleReadReceipt(session, userId, data);
        case WsEvents.CHAT_RECALL -> handleRecall(session, userId, data);
        case WsEvents.CHAT_TYPING, WsEvents.GROUP_JOIN, WsEvents.GROUP_LEAVE ->
            handleUnsupportedBusinessEvent(session, event);
        case WsEvents.RTC_SIGNAL -> handleRtcSignal(session, userId, data);
        default -> {
          log.warn("收到不支持的 WebSocket 事件, userId={}, sessionId={}, event={}", userId, session.getId(), event);
          wsBroadcastService.sendToSession(session, WsEvents.ERROR, Map.of("message", "Unknown event"));
        }
      }
    } catch (Exception ex) {
      // 业务载荷解析/处理失败绝不允许断开连接（Spring 的 ExceptionWebSocketHandlerDecorator
      // 会把异常以 1011 强踢整个会话，导致后续所有消息一直「发送中」）。
      // 改为向客户端回发错误帧，保持连接存活。
      log.error(
          "Failed to handle websocket event, userId={}, sessionId={}, event={}",
          userId,
          session.getId(),
          event,
          ex);
      try {
        wsBroadcastService.sendToSession(
            session,
            WsEvents.ERROR,
            Map.of(
                "code", "WS_EVENT_HANDLING_FAILED",
                "event", event,
                "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
      } catch (Exception sendEx) {
        log.warn(
            "Failed to send websocket error frame, userId={}, sessionId={}, event={}",
            userId,
            session.getId(),
            event,
            sendEx);
      }
    }
  }

  private void handleAppState(long userId, Map<String, Object> data) {
    String appState = data.get("appState") == null ? "background" : String.valueOf(data.get("appState"));
    String activeConversationId = data.get("activeConversationId") == null ? ""
        : String.valueOf(data.get("activeConversationId"));
    devicePresenceRegistry.report(userId, appState, activeConversationId);
    log.debug("Received app state report, userId={}, appState={}", userId, appState);
  }

  private void handleChatSend(WebSocketSession session, Long userId, String username, Map<String, Object> data)
      throws Exception {
    SendMessageDto dto = objectMapper.convertValue(data, SendMessageDto.class);
    // 私密聊天消息仅允许走 HTTP /secret-messages 接口（E2EE 密文链路），禁止经 WS 发送。
    if (dto.getChatType() == io.openware.common.enums.ChatType.SECRET) {
      log.warn(
          "Rejected secret message via websocket chat:send, userId={}, sessionId={}, clientMsgId={}",
          userId,
          session.getId(),
          dto.getClientMsgId());
      wsBroadcastService.sendToSession(
          session,
          WsEvents.ERROR,
          Map.of(
              "code", "SECRET_VIA_WS_NOT_SUPPORTED",
              "event", WsEvents.CHAT_SEND,
              "clientMsgId", dto.getClientMsgId() == null ? "" : dto.getClientMsgId(),
              "message", "Secret messages must be sent via /secret-messages API."));
      return;
    }
    log.info(
        "收到聊天发送请求, userId={}, sessionId={}, clientMsgId={}, chatType={}, toId={}, msgType={}",
        userId,
        session.getId(),
        dto.getClientMsgId(),
        dto.getChatType(),
        dto.getToId(),
        dto.getMsgType());
    MessageAcceptance accepted = mqMessageGateway.accept(userId, username, dto);
    log.info(
        "Accepted chat send request, userId={}, clientMsgId={}, msgId={}, acceptedAt={}",
        userId,
        dto.getClientMsgId(),
        accepted.msgId(),
        accepted.acceptedAt());
    Map<String, Object> ack = new HashMap<>();
    ack.put("clientMsgId", dto.getClientMsgId());
    ack.put("msgId", accepted.msgId());
    ack.put("content", dto.getContent());
    ack.put("timestamp", accepted.acceptedAt());
    wsBroadcastService.sendToSession(session, WsEvents.CHAT_ACK, ack);
  }

  private void handleReadReceipt(WebSocketSession session, Long userId, Map<String, Object> data) throws Exception {
    ReadReceiptDto dto = objectMapper.convertValue(data, ReadReceiptDto.class);
    log.info(
        "Processing read receipt, userId={}, sessionId={}, messageCount={}",
        userId,
        session.getId(),
        dto.getMsgIds() == null ? 0 : dto.getMsgIds().size());
    var accepted = mqMessageGateway.acceptRead(userId, dto);
    wsBroadcastService.sendToSession(session, WsEvents.CHAT_READ, Map.of(
        "commandId", accepted.commandId(), "acceptedAt", accepted.acceptedAt().toString()));
  }

  private void handleRecall(WebSocketSession session, Long userId, Map<String, Object> data) throws Exception {
    RecallMessageDto dto = objectMapper.convertValue(data, RecallMessageDto.class);
    var accepted = mqMessageGateway.acceptRecall(userId, dto);
    log.info(
        "Accepted message recall command, userId={}, sessionId={}, msgId={}, commandId={}",
        userId,
        session.getId(),
        dto.getMsgId(),
        accepted.commandId());
    wsBroadcastService.sendToSession(session, WsEvents.CHAT_RECALL, Map.of(
        "msgId", dto.getMsgId(), "commandId", accepted.commandId(), "acceptedAt", accepted.acceptedAt().toString()));
  }

  private void handleUnsupportedBusinessEvent(WebSocketSession session, String event) {
    wsBroadcastService.sendToSession(session, WsEvents.ERROR, Map.of(
        "code", "AUTHORITATIVE_CONTRACT_UNAVAILABLE", "event", event));
  }

  private void handleRtcSignal(WebSocketSession session, long userId, Map<String, Object> data) {
    WsRtcSignalDto signal = objectMapper.convertValue(data, WsRtcSignalDto.class);
    if (signal.getTargetUserId() == null
        || signal.getTargetUserId() <= 0
        || signal.getTargetUserId() == userId
        || signal.getAction() == null
        || signal.getAction().isBlank()) {
      wsBroadcastService.sendToSession(session, WsEvents.ERROR, Map.of(
          "code", "INVALID_RTC_SIGNAL"));
      return;
    }

    Map<String, Object> forwarded = objectMapper.convertValue(signal, new TypeReference<Map<String, Object>>() {});
    forwarded.put("fromUserId", userId);

    // Redis Pub/Sub 是 fire-and-forget，sendToUser 的返回值无法反映被叫是否在线；
    // 因此来电邀请的离线推送决策改为「被叫设备是否在前台」，与消息离线推送保持一致。
    // 被叫在前台 → 仅 WS 信令；后台/断线 → 补发极光来电推送，点击通知后 App 内拉起接听界面。
    boolean wsPublished = wsBroadcastService.sendToUser(signal.getTargetUserId(), WsEvents.RTC_SIGNAL, forwarded);
    if ("call".equalsIgnoreCase(signal.getAction()) || "ring".equalsIgnoreCase(signal.getAction())) {
      if (!devicePresenceRegistry.isAppForeground(signal.getTargetUserId())) {
        jpushMessageService.pushCallInviteIfConfigured(
            signal.getTargetUserId(), userId, signal.getCallId(), signal.getMediaType());
      }
    }

    // 仅在 Redis 发布失败（基础设施故障）时回 unavailable，避免主叫一直响铃。
    if (!wsPublished) {
      wsBroadcastService.sendToSession(session, WsEvents.RTC_SIGNAL, Map.of(
          "action", "unavailable", "reason", "delivery_unavailable", "callId", signal.getCallId()));
    }
  }
}
