package io.openware.protocol.mq.group;

public final class ImMqProducerGroups {
  public static final String MESSAGE_SERVICE = "im-message-service-producer";
  public static final String ACCESS_WS = "im-access-ws-producer";
  public static final String USER_SERVICE = "im-user-service-producer";
  /** 会话服务经自己的 Outbox（ConversationAuthorizationOutboxRelay）发布授权变更事件。 */
  public static final String CONVERSATION_SERVICE = "im-conversation-service-producer";

  private ImMqProducerGroups() {
  }
}
