package io.openware.protocol.mq.group;

public final class ImMqConsumerGroups {
  public static final String MESSAGE_SERVICE_WRITE_COMMAND = "im-message-service-write-command-consumer";
  public static final String MESSAGE_SERVICE_FRIEND_ACCEPTED = "im-message-service-friend-accepted-consumer";
  public static final String MESSAGE_SERVICE_READ_COMMAND = "im-message-service-read-command-consumer";
  public static final String MESSAGE_SERVICE_RECALL_COMMAND = "im-message-service-recall-command-consumer";
  public static final String MESSAGE_SERVICE_SECRET_DESTROY = "im-message-service-secret-destroy-consumer";
  public static final String ACCESS_WS_DELIVERY = "im-access-ws-delivery-consumer";
  public static final String ACCESS_WS_SECRET_NOTIFICATION = "im-access-ws-secret-notification-consumer";
  public static final String ACCESS_WS_SECRET_DESTROYED = "im-access-ws-secret-destroyed-consumer";
  public static final String ACCESS_WS_RECALL_NOTIFICATION = "im-access-ws-recall-notification-consumer";
  public static final String ACCESS_WS_EDIT_NOTIFICATION = "im-access-ws-edit-notification-consumer";
  public static final String ACCESS_WS_CHAT_CLEARED_NOTIFICATION = "im-access-ws-chat-cleared-notification-consumer";
  public static final String ACCESS_WS_CONVERSATION_NOTIFICATION = "im-access-ws-conversation-notification-consumer";
  public static final String ACCESS_WS_SECRET_CHAT_CREATED = "im-access-ws-secret-chat-created-consumer";
  public static final String ACCESS_WS_SECRET_GROUP_NOTIFICATION = "im-access-ws-secret-group-notification-consumer";
  public static final String ACCESS_WS_FRIEND_NOTIFICATION = "im-access-ws-friend-notification-consumer";
  public static final String ACCESS_WS_AUTHENTICATION_INVALIDATION = "im-access-ws-authentication-invalidation-consumer";
  public static final String ACCESS_WS_DATA_WIPE = "im-access-ws-data-wipe-consumer";
  public static final String ADMIN_PROJECTION_USER_STATUS = "im-admin-service-user-status-projection-consumer";
  public static final String IDENTITY_SERVICE_USER_PROFILE_CHANGED = "platform-identity-service-user-profile-changed-consumer";
  public static final String ADMIN_PROJECTION_MESSAGE = "im-admin-service-message-projection-consumer";
  public static final String MESSAGE_SERVICE_CHAT_RECORDS_PURGE = "im-message-service-chat-records-purge-consumer";
  public static final String CONVERSATION_SERVICE_CHAT_RECORDS_PURGE = "im-conversation-service-chat-records-purge-consumer";
  public static final String MESSAGE_SERVICE_SECRET_CHAT_DELETED = "im-message-service-secret-chat-deleted-consumer";
  public static final String ACCESS_WS_SECRET_CHAT_DELETED = "im-access-ws-secret-chat-deleted-consumer";
  public static final String MESSAGE_SERVICE_SECRET_CHAT_DESTROY_POLICY_CHANGED = "im-message-service-secret-chat-destroy-policy-changed-consumer";

  private ImMqConsumerGroups() {
  }
}
