package com.gvchat.protocol.mq.command;

import java.time.Instant;
import java.util.List;
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
public class MessageReadCommand {
  private String commandId;
  private long userId;
  private List<String> msgIds;
  private Instant acceptedAt;
}
