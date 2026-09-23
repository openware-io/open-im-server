package io.openware.im.message.api.dto.response;

import java.util.List;

public record MessageSyncResponse(List<SyncedMessageResponse> items, long nextSyncSeq, boolean hasMore,
    List<ClearedConversationResponse> clearedConversations, List<DeletedMessageResponse> deletedMessages) {

  /**
   * 会话清空标记：客户端删除本地**早于 clearedAtEpochMs** 的消息。
   *
   * <p>用 epoch 毫秒而非日期字符串，避免客户端对服务端 LocalDateTime(UTC) 的时区解析歧义。
   */
  public record ClearedConversationResponse(String conversationId, String chatType, long clearedAtEpochMs) {
  }

  /** 「删除仅我」墓碑：带 conversationId/chatType，客户端才能定位并清理会话预览。 */
  public record DeletedMessageResponse(String msgId, String conversationId, String chatType) {
  }
}