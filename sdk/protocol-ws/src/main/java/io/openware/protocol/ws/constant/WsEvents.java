package io.openware.protocol.ws.constant;

/**
 */
public final class WsEvents {
  private WsEvents() {}

  /* --- 聊天消息 --- */
  public static final String CHAT_SEND = "chat:send";
  public static final String CHAT_RECEIVE = "chat:receive";
  public static final String CHAT_ACK = "chat:ack";
  public static final String CHAT_READ = "chat:read";
  public static final String CHAT_READ_NOTIFY = "chat:read_notify";
  public static final String CHAT_RECALL = "chat:recall";
  public static final String CHAT_RECALL_NOTIFY = "chat:recall_notify";
  public static final String CHAT_DELETE_NOTIFY = "chat:delete_notify";
  public static final String CHAT_EDIT_NOTIFY = "chat:edit_notify";
  public static final String CHAT_SECRET_DESTROYED = "chat:secret_destroyed";
  public static final String CHAT_SECRET_STORED = "chat:secret_stored";
  public static final String CHAT_SECRET_CREATED = "chat:secret_created";
  public static final String CHAT_SECRET_DELETED = "chat:secret_deleted";
  public static final String CHAT_SECRET_GROUP_STORED = "chat:secret_group_stored";
  public static final String CHAT_CLEAR_PRIVATE_NOTIFY = "chat:clear_private_notify";
  public static final String CHAT_CLEAR_GROUP_NOTIFY = "chat:clear_group_notify";
  public static final String CHAT_TYPING = "chat:typing";

  /* --- 用户状态 --- */
  public static final String USER_ONLINE = "user:online";
  public static final String USER_OFFLINE = "user:offline";
  public static final String USER_STATUS_CHANGE = "user:status_change";

  /* --- 好友关系 --- */
  public static final String FRIEND_REQUEST = "friend:request";
  public static final String FRIEND_REQUEST_NOTIFY = "friend:request_notify";
  public static final String FRIEND_ACCEPT = "friend:accept";
  public static final String FRIEND_ACCEPT_NOTIFY = "friend:accept_notify";

  /* --- 群组事件 --- */
  public static final String GROUP_JOIN = "group:join";
  public static final String GROUP_LEAVE = "group:leave";
  public static final String GROUP_NOTIFY = "group:notify";

  /* --- RTC 与连接 --- */
  public static final String RTC_SIGNAL = "rtc:signal";
  public static final String HEARTBEAT = "heartbeat";
  public static final String ERROR = "error";

  /* --- 设备前后台状态 --- */
  public static final String APP_STATE = "app:state";
}
