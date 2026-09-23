package io.openware.im.message.infra.projection;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("msg_hot_message")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HotMessageDocument {
  @Id
  private String id;
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
  private boolean edited;
  private Instant editedAt;
  private Instant createdAt;
  private Instant expiresAt;
}
