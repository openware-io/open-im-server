package io.openware.im.message.application.favorite.result;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.time.LocalDateTime;

/**
 * 收藏列表单项：msgId/peerId/chatType/favoritedAt 来自收藏记录本身；
 * msgType/content/senderId/senderUsername/createdAt 优先来自回查到的权威消息（保证看到编辑后的最新内容），
 * 权威消息已被撤回/删除或当前用户已无权访问时回落到收藏时快照。
 * 只有快照列（V13）之前的历史行两者都取不到时，消息侧字段才为 null，由展示层渲染占位。
 */
public record FavoriteResult(String msgId, String peerId, ChatType chatType, MsgType msgType, String content,
    Long senderId, String senderUsername, LocalDateTime createdAt, LocalDateTime favoritedAt) {

  public static FavoriteResult placeholder(String msgId, String peerId, ChatType chatType,
      LocalDateTime favoritedAt) {
    return new FavoriteResult(msgId, peerId, chatType, null, null, null, null, null, favoritedAt);
  }
}
