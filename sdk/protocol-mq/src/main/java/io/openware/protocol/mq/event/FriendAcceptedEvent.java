package io.openware.protocol.mq.event;

import java.time.Instant;
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
public class FriendAcceptedEvent {
  private String eventId;
  private Long requestId;
  private Long fromUserId;
  private Long toUserId;
  private Instant occurredAt;
}
