package com.gvchat.protocol.mq.support;

public final class ConversationIds {
  private ConversationIds() {
  }

  public static String privateConversation(long leftUserId, long rightUserId) {
    long min = Math.min(leftUserId, rightUserId);
    long max = Math.max(leftUserId, rightUserId);
    return "conv:private:" + min + ":" + max;
  }

  public static String groupConversation(long groupId) {
    return "conv:group:" + groupId;
  }

  public static String channelConversation(long channelId) {
    return "conv:channel:" + channelId;
  }
}
