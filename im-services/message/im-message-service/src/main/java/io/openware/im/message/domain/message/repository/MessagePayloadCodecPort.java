package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.event.MessageEditedEvent;
import io.openware.im.message.domain.message.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import io.openware.protocol.mq.event.ChatClearedEvent;
import io.openware.protocol.mq.event.MessageMedia;
import io.openware.protocol.mq.event.MessageRecalledEvent;

public interface MessagePayloadCodecPort {
  List<String> readAtUsers(String atUsersJson);

  /**
   * 序列化「消息已存储」事件。{@code sentAt} 是消息发送时刻：审计列不在领域模型上，
   * 由应用层从 {@code MessageRepository.Persisted.audit().createdAt()} 取得后显式传入。
   */
  String writeStoredMessageEvent(Message message, LocalDateTime sentAt, List<Long> recipientUserIds,
      Map<Long, Long> recipientSyncSeqs, List<MessageMedia> media);

  String writeMessageRecalledEvent(MessageRecalledEvent event);

  String writeMessageEditedEvent(MessageEditedEvent event);

  /** 序列化「会话记录被清空」事件，用于通知其余受影响成员清理端缓存与投影数据。 */
  String writeChatClearedEvent(ChatClearedEvent event);
}
