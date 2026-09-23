package io.openware.im.message.api.converter;

import io.openware.im.message.api.dto.response.DeleteMessageResponse;
import io.openware.im.message.api.dto.response.EditMessageResponse;
import io.openware.im.message.api.dto.response.MessageResponse;
import io.openware.im.message.api.dto.response.MessageSyncResponse;
import io.openware.im.message.api.dto.response.SearchMessagesResponse;
import io.openware.im.message.api.dto.response.SyncedMessageResponse;
import io.openware.im.message.application.result.DeleteMessageResult;
import io.openware.im.message.application.result.EditMessageResult;
import io.openware.im.message.application.result.MessageResult;
import io.openware.im.message.application.result.MessageSyncResult;
import io.openware.im.message.application.result.SearchMessagesResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class MessageApiConverter {
  private MessageApiConverter() { }

  public static MessageResponse toResponse(MessageResult result) {
    return new MessageResponse(result.id(), result.msgId(), result.conversationId(), result.seq(), result.fromUserId(),
        result.senderUsername(), result.toId(), result.chatType(), result.msgType(), result.content(), result.clientMsgId(),
        result.replyMsgId(), result.atUsers(), result.status(), result.edited(), utc(result.editedAt()), result.createdBy(),
        utc(result.createdAt()), result.updatedBy(), utc(result.updatedAt()), result.media());
  }

  public static SearchMessagesResponse toResponse(SearchMessagesResult result) {
    return new SearchMessagesResponse(result.items().stream().map(MessageApiConverter::toResponse).toList(),
        result.total(), result.page(), result.pageSize());
  }

  public static MessageSyncResponse toResponse(MessageSyncResult result) {
    return new MessageSyncResponse(result.items().stream()
        .map(item -> new SyncedMessageResponse(item.syncSeq(), toResponse(item.message()), utc(item.readAt()))).toList(),
        result.nextSyncSeq(), result.hasMore(), toClearedConversations(result),
        toDeletedMessages(result));
  }

  /** 「删除仅我」墓碑：带 conversationId/chatType，客户端才能定位并清理会话预览。 */
  private static java.util.List<MessageSyncResponse.DeletedMessageResponse> toDeletedMessages(
      MessageSyncResult result) {
    if (result.deletedMessages() == null) {
      return java.util.List.of();
    }
    return result.deletedMessages().stream()
        .map(item -> new MessageSyncResponse.DeletedMessageResponse(item.msgId(), item.conversationId(),
            item.chatType()))
        .toList();
  }

  /** 会话清空标记转为 epoch 毫秒，避免客户端对服务端 LocalDateTime(UTC) 的时区解析歧义。 */
  private static java.util.List<MessageSyncResponse.ClearedConversationResponse> toClearedConversations(
      MessageSyncResult result) {
    if (result.clearedConversations() == null) {
      return java.util.List.of();
    }
    return result.clearedConversations().stream()
        .map(cleared -> new MessageSyncResponse.ClearedConversationResponse(cleared.conversationId(),
            cleared.chatType(), cleared.clearedAt() == null ? 0L
                : cleared.clearedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()))
        .toList();
  }

  public static DeleteMessageResponse toResponse(DeleteMessageResult result) {
    return new DeleteMessageResponse(result.ok(), result.msgId(), result.chatType(), result.toId(), result.fromUserId());
  }

  public static EditMessageResponse toResponse(EditMessageResult result) {
    return new EditMessageResponse(result.msgId(), result.edited(), result.editedAt());
  }

  private static Instant utc(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
