package io.openware.im.message.domain.message.port;

import io.openware.im.message.domain.message.event.MessageEditedEvent;
import io.openware.im.message.domain.message.model.Message;
import java.util.List;
import java.util.Map;
import io.openware.protocol.mq.event.ChatClearedEvent;
import io.openware.protocol.mq.event.MessageMedia;
import io.openware.protocol.mq.event.MessageRecalledEvent;

public interface MessagePayloadCodecPort {
  List<String> readAtUsers(String atUsersJson);

  String writeStoredMessageEvent(Message message, List<Long> recipientUserIds, Map<Long, Long> recipientSyncSeqs,
      List<MessageMedia> media);

  String writeMessageRecalledEvent(MessageRecalledEvent event);

  String writeMessageEditedEvent(MessageEditedEvent event);

  /** 序列化「会话记录被清空」事件，用于通知其余受影响成员清理端缓存与投影数据。 */
  String writeChatClearedEvent(ChatClearedEvent event);
}
