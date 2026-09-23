package io.openware.protocol.mq.command;

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
public class MessageRecallCommand {
  private String commandId;
  private long userId;
  private String msgId;
  private Instant acceptedAt;
}
