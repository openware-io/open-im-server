package com.gvchat.im.message.application.result;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步结果。
 *
 * <p>{@code clearedConversations}：会话清空标记（实时 WS 通知在对方离线时会丢失，
 * 客户端据此删除本地早于 clearedAt 的消息）。
 *
 * <p>{@code deletedMessages}：该用户「删除仅我」的墓碑。**必须带会话定位信息** —— 客户端在
 * 未打开该会话时内存里没有消息，仅凭 msgId 无法定位要清理的会话预览（真机实测到的缺陷）。
 */
public record MessageSyncResult(List<SyncedMessageResult> items, long nextSyncSeq, boolean hasMore,
    List<ClearedConversationResult> clearedConversations, List<DeletedMessageResult> deletedMessages) {

  /** 会话清空标记（per-user）。 */
  public record ClearedConversationResult(String conversationId, String chatType, LocalDateTime clearedAt) {
  }

  /** 「删除仅我」墓碑（含会话定位信息）。 */
  public record DeletedMessageResult(String msgId, String conversationId, String chatType) {
  }
}