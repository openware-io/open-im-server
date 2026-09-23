package io.openware.common.constant;

/**
 * Redis 键名常量，供在线状态、WebSocket 路由与消息去重等模块使用。
 */
public final class RedisKeys {
  private RedisKeys() {}

  /* --- 在线与连接映--- */
  public static final String ONLINE_SET = "im:online";
  public static final String USER_SOCKETS = "im:user:sockets:";
  public static final String SOCKET_USER = "im:socket:user:";

  /* --- 未读与消息序--- */
  public static final String UNREAD_COUNT = "im:unread:";
  public static final String ADMIN_MESSAGE_TOTAL = "im:admin:message:total";
  public static final String MSG_DEDUP = "im:msg:dedup:";
  public static final String EVENT_DEDUP = "im:event:dedup:";
  public static final String USER_LAST_SEQ = "im:user:last_seq:";
  public static final String AUTHENTICATION_USER = "im:auth:user:";
  public static final String WS_TICKET = "im:ws:ticket:";
  public static final String INTERNAL_AUTH_REQUEST = "im:internal:auth:request:";
  public static final String PASSWORD_RESET_TOKEN = "im:password:reset:";
  public static final String QR_LOGIN = "im:qr:login:";

  /* --- 跨节点广--- */
  public static final String WS_BROADCAST = "im:ws:broadcast";
}
