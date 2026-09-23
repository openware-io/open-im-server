package io.openware.im.accessws.util;

public final class WsRoomUtil {
  private WsRoomUtil() {}

  public static String userRoom(long userId) {
    return "user:" + userId;
  }

  /**
   *
   * @return 格式{@code user:{userId}} 的房间标诡
   */
}
