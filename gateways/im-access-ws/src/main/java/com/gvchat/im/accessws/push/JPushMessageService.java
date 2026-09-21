package com.gvchat.im.accessws.push;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class JPushMessageService {
  private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
      new ParameterizedTypeReference<>() {
      };
  private static final ObjectMapper JSON = new ObjectMapper();

  private final JPushProperties properties;
  private final UserDeviceTokenClient deviceTokenClient;
  private final MessageUnreadClient messageUnreadClient;
  private final ConversationMuteClient conversationMuteClient;
  private final RestClient restClient;

  public JPushMessageService(
      JPushProperties properties,
      UserDeviceTokenClient deviceTokenClient,
      MessageUnreadClient messageUnreadClient,
      ConversationMuteClient conversationMuteClient) {
    this.properties = properties;
    this.deviceTokenClient = deviceTokenClient;
    this.messageUnreadClient = messageUnreadClient;
    this.conversationMuteClient = conversationMuteClient;
    this.restClient = RestClient.create();
  }

  public void pushIfConfigured(long userId, MessageStoredEvent event) {
    if (!isPushEnabledForChatType(userId, event.getChatType())) {
      log.info("Skipped offline push by user notification setting, msgId={}, userId={}, chatType={}",
          event.getMsgId(), userId, event.getChatType());
      return;
    }
    // @提及穿透单会话免打扰：即使会话已 muted，被 @ 的用户仍应收到离线推送。
    if (isConversationMuted(userId, event.getConversationId())
        && !isAtMentioned(userId, event.getAtUsersJson())) {
      log.info("Skipped offline push by per-conversation mute, msgId={}, userId={}, conversationId={}",
          event.getMsgId(), userId, event.getConversationId());
      return;
    }
    List<String> registrationIds = registrationIdsOf(userId);
    if (registrationIds.isEmpty()) {
      return;
    }
    long badge = safeUnreadCount(userId);
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload(registrationIds, event, badge)).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted offline message notification, msgId={}, jpushMsgId={}, userId={}, registrationCount={}",
        event.getMsgId(), response == null ? null : response.get("msg_id"), userId, registrationIds.size());
  }

  private boolean isPushEnabledForChatType(long userId, String chatType) {
    Map<String, Boolean> settings = deviceTokenClient.findNotificationSettings(userId);
    if (settings == null || settings.isEmpty()) {
      return true;
    }
    Boolean enabled = switch (chatType == null ? "" : chatType) {
      case "group" -> settings.getOrDefault("notifyGroup", true);
      case "channel" -> settings.getOrDefault("notifyChannel", true);
      case "private", "secret" -> settings.getOrDefault("notifyPrivate", true);
      default -> true;
    };
    return enabled == null || enabled;
  }

  /**
   * 私密消息离线通知：接收方离线时推送「你收到一条加密消息」（**绝不含明文/密文内容**）。
   * 消息内容仅能由接收方在 App 内解密后查看，推送只做到达提示。
   */
  public void pushSecretIfConfigured(long userId, long secretChatId, long senderId) {
    if (!properties.configured()) {
      return;
    }
    if (isConversationMuted(userId, "secret:" + secretChatId)) {
      log.info("Skipped offline secret push by per-conversation mute, secretChatId={}, userId={}", secretChatId, userId);
      return;
    }
    List<String> registrationIds = registrationIdsOf(userId);
    if (registrationIds.isEmpty()) {
      return;
    }
    long badge = safeUnreadCount(userId);
    Map<String, Object> extras = Map.of(
        "secretChatId", secretChatId, "fromUserId", senderId, "chatType", "secret");
    Map<String, Object> body = Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", "加密消息", "alert", "你收到一条加密消息", "extras", extras),
            "ios", Map.of("alert", Map.of("title", "加密消息", "body", "你收到一条加密消息"),
                "sound", "default", "badge", badge, "extras", extras)),
        "options", Map.of("time_to_live", Math.max(0, properties.getTimeToLiveSeconds()),
            "apns_production", properties.isApnsProduction()));
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted offline secret message notification, secretChatId={}, jpushMsgId={}, userId={}, registrationCount={}",
        secretChatId, response == null ? null : response.get("msg_id"), userId, registrationIds.size());
  }

  /**
   * 私密群聊消息离线通知：接收方离线时推送「你收到一条加密群聊消息」（**绝不含明文/密文内容**）。
   * 内容仅能由接收方在 App 内解密后查看，推送只做到达提示。
   */
  public void pushSecretGroupIfConfigured(long userId, long secretGroupId, long senderId, List<Long> atUserIds) {
    if (!properties.configured()) {
      return;
    }
    // @提及穿透免打扰：被 @ 的成员即使会话已 muted 仍收到离线推送。
    if (isConversationMuted(userId, "secret_group:" + secretGroupId)
        && (atUserIds == null || !atUserIds.contains(userId))) {
      log.info("Skipped offline secret group push by per-conversation mute, secretGroupId={}, userId={}",
          secretGroupId, userId);
      return;
    }
    List<String> registrationIds = registrationIdsOf(userId);
    if (registrationIds.isEmpty()) {
      return;
    }
    long badge = safeUnreadCount(userId);
    Map<String, Object> extras = Map.of(
        "secretGroupId", secretGroupId, "fromUserId", senderId, "chatType", "secret_group");
    Map<String, Object> body = Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", "加密群聊消息", "alert", "你收到一条加密群聊消息", "extras", extras),
            "ios", Map.of("alert", Map.of("title", "加密群聊消息", "body", "你收到一条加密群聊消息"),
                "sound", "default", "badge", badge, "extras", extras)),
        "options", Map.of("time_to_live", Math.max(0, properties.getTimeToLiveSeconds()),
            "apns_production", properties.isApnsProduction()));
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted offline secret group message notification, secretGroupId={}, jpushMsgId={}, userId={}, registrationCount={}",
        secretGroupId, response == null ? null : response.get("msg_id"), userId, registrationIds.size());
  }

  /**
   * 来电邀请离线推送：被叫离线（WS 已断开）时推送「语音/视频通话邀请」。
   * 普通通知类型（极光无厂商 VoIP 白名单），点击通知后 App 内拉起接听界面；
   * 呼叫有时效性，time_to_live 取 60 秒避免过期通知打扰。
   */
  public void pushCallInviteIfConfigured(long targetUserId, long fromUserId, String callId, String mediaType) {
    if (!properties.configured()) {
      return;
    }
    List<String> registrationIds = registrationIdsOf(targetUserId);
    if (registrationIds.isEmpty()) {
      return;
    }
    boolean video = "video".equalsIgnoreCase(mediaType);
    String title = video ? "视频通话邀请" : "语音通话邀请";
    String alert = video ? "邀请你进行视频通话" : "邀请你进行语音通话";
    Map<String, Object> extras = Map.of(
        "chatType", "call",
        "callId", callId == null ? "" : callId,
        "fromUserId", fromUserId,
        "mediaType", video ? "video" : "audio");
    Map<String, Object> body = Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", title, "alert", alert, "extras", extras),
            "ios", Map.of("alert", Map.of("title", title, "body", alert),
                "sound", "default", "badge", safeUnreadCount(targetUserId), "extras", extras)),
        "options", Map.of("time_to_live", 60, "apns_production", properties.isApnsProduction()));
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted call invite notification, callId={}, jpushMsgId={}, userId={}, registrationCount={}",
        callId, response == null ? null : response.get("msg_id"), targetUserId, registrationIds.size());
  }

  /** 好友申请离线通知：接收方离线时推送「你收到一条好友申请」（不含申请附言，避免泄露）。 */
  public void pushFriendRequestIfConfigured(long toUserId, long requestId, long fromUserId) {
    if (!properties.configured()) {
      return;
    }
    List<String> registrationIds = registrationIdsOf(toUserId);
    if (registrationIds.isEmpty()) {
      return;
    }
    long badge = safeUnreadCount(toUserId);
    Map<String, Object> extras = Map.of(
        "requestId", requestId, "fromUserId", fromUserId, "chatType", "friend_request");
    Map<String, Object> body = Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", "好友申请", "alert", "你收到一条好友申请", "extras", extras),
            "ios", Map.of("alert", Map.of("title", "好友申请", "body", "你收到一条好友申请"),
                "sound", "default", "badge", badge, "extras", extras)),
        "options", Map.of("time_to_live", Math.max(0, properties.getTimeToLiveSeconds()),
            "apns_production", properties.isApnsProduction()));
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted offline friend request notification, requestId={}, jpushMsgId={}, userId={}, registrationCount={}",
        requestId, response == null ? null : response.get("msg_id"), toUserId, registrationIds.size());
  }

  /** 好友同意离线通知：申请方离线时推送「对方已同意你的好友申请」。 */
  public void pushFriendAcceptedIfConfigured(long toUserId, long friendId) {
    if (!properties.configured()) {
      return;
    }
    List<String> registrationIds = registrationIdsOf(toUserId);
    if (registrationIds.isEmpty()) {
      return;
    }
    long badge = safeUnreadCount(toUserId);
    Map<String, Object> extras = Map.of("friendId", friendId, "chatType", "friend_accept");
    Map<String, Object> body = Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", "好友通知", "alert", "对方已同意你的好友申请", "extras", extras),
            "ios", Map.of("alert", Map.of("title", "好友通知", "body", "对方已同意你的好友申请"),
                "sound", "default", "badge", badge, "extras", extras)),
        "options", Map.of("time_to_live", Math.max(0, properties.getTimeToLiveSeconds()),
            "apns_production", properties.isApnsProduction()));
    Map<String, Object> response = restClient.post().uri(endpoint())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(RESPONSE_TYPE);
    log.info(
        "JPush accepted offline friend accept notification, friendId={}, jpushMsgId={}, userId={}, registrationCount={}",
        friendId, response == null ? null : response.get("msg_id"), toUserId, registrationIds.size());
  }

  private List<String> registrationIdsOf(long userId) {
    return deviceTokenClient.findEnabledTokens(userId).stream()
        .filter(token -> "jpush".equalsIgnoreCase(token.get("pushProvider")))
        .map(token -> token.get("token"))
        .filter(Objects::nonNull).filter(token -> !token.isBlank()).distinct().toList();
  }

  private String basicAuthorization() {
    String value = properties.getAppKey() + ":" + properties.getMasterSecret();
    return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private URI endpoint() {
    URI endpoint = URI.create(properties.getEndpoint());
    if (!endpoint.isAbsolute() || endpoint.getScheme() == null || endpoint.getScheme().isBlank()) {
      throw new IllegalStateException("JPush endpoint must be an absolute URI");
    }
    return endpoint;
  }

  private Map<String, Object> payload(List<String> registrationIds, MessageStoredEvent event, long badge) {
    String title = properties.isIncludeContent() && event.getSenderUsername() != null && !event.getSenderUsername().isBlank()
        ? event.getSenderUsername() : "新消息";
    String content = properties.isIncludeContent() && event.getContent() != null && !event.getContent().isBlank()
        ? event.getContent() : "你收到一条新消息";
    Map<String, Object> extras = new java.util.HashMap<>();
    extras.put("msgId", event.getMsgId());
    extras.put("conversationId", event.getConversationId());
    extras.put("fromUserId", event.getSenderId());
    extras.put("chatType", event.getChatType());
    extras.put("badge", badge);
    return Map.of(
        "platform", List.of("android", "ios"),
        "audience", Map.of("registration_id", registrationIds),
        "notification", Map.of(
            "android", Map.of("title", title, "alert", content, "extras", extras),
            "ios", Map.of("alert", Map.of("title", title, "body", content), "sound", "default",
                "badge", badge, "extras", extras)),
        "options", Map.of("time_to_live", Math.max(0, properties.getTimeToLiveSeconds()),
            "apns_production", properties.isApnsProduction()));
  }

  /** 接收方是否被 @ 提及；解析失败时按未提及处理（fail-open，不改变 mute 过滤结果）。 */
  private boolean isAtMentioned(long userId, String atUsersJson) {
    if (atUsersJson == null || atUsersJson.isBlank()) {
      return false;
    }
    try {
      List<String> atUsers = JSON.readValue(atUsersJson, new TypeReference<List<String>>() {
      });
      return atUsers.contains(String.valueOf(userId));
    } catch (Exception ex) {
      log.warn("Failed to parse atUsers for mention check, userId={}", userId, ex);
      return false;
    }
  }

  /** 会话是否被用户免打扰；客户端为空或会话标识为空时视为未免打扰（fail-open）。 */
  private boolean isConversationMuted(long userId, String conversationId) {
    if (conversationMuteClient == null || conversationId == null || conversationId.isBlank()) {
      return false;
    }
    return conversationMuteClient.isMuted(userId, conversationId);
  }

  /** 服务端权威绝对未读总数；拉取失败时保守返回 0（角标不影响消息到达）。 */
  private long safeUnreadCount(long userId) {
    if (messageUnreadClient == null) {
      return 0;
    }
    try {
      return messageUnreadClient.unreadCount(userId);
    } catch (RuntimeException ex) {
      log.warn("Failed to fetch unread count for badge, userId={}", userId, ex);
      return 0;
    }
  }
}
