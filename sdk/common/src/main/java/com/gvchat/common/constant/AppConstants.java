package com.gvchat.common.constant;

/**
 */
public final class AppConstants {
  private AppConstants() {}

  /* --- 分页默--- */
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /* --- WebSocket 心跳 --- */
  public static final long HEARTBEAT_INTERVAL_MS = 30_000L;
  public static final long HEARTBEAT_TIMEOUT_MS = 90_000L;

  /* --- 消息去重与撤--- */
  public static final long MSG_DEDUP_TTL_SEC = 86_400L;
  public static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;
}
