package io.openware.protocol.mq.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageStoredEvent {
  private String eventId;
  private String conversationId;
  private long seq;
  private String msgId;
  private long senderId;
  private String senderUsername;
  private String clientMsgId;
  private String chatType;
  private String toId;
  private String msgType;
  private String content;
  private String replyMsgId;
  private String atUsersJson;
  private List<MessageMedia> media;
  private List<Long> recipientUserIds;
  private Map<Long, Long> recipientSyncSeqs;
  private Instant createdAt;
}
