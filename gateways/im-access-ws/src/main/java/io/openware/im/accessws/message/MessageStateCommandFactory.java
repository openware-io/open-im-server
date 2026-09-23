package io.openware.im.accessws.message;

import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.protocol.mq.command.MessageReadCommand;
import io.openware.protocol.mq.command.MessageRecallCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MessageStateCommandFactory {
  private final Supplier<String> commandIdSupplier;
  private final Clock clock;

  @Autowired
  public MessageStateCommandFactory(SnowflakeIdGenerator idGenerator) {
    this(idGenerator::nextId, Clock.systemUTC());
  }

  MessageStateCommandFactory(Supplier<String> commandIdSupplier, Clock clock) {
    this.commandIdSupplier = commandIdSupplier;
    this.clock = clock;
  }

  public PreparedReadCommand createRead(long userId, List<String> msgIds) {
    String commandId = commandIdSupplier.get();
    Instant acceptedAt = Instant.now(clock);
    return new PreparedReadCommand(
        MessageReadCommand.builder()
            .commandId(commandId)
            .userId(userId)
            .msgIds(List.copyOf(msgIds))
            .acceptedAt(acceptedAt)
            .build(),
        new CommandAcceptance(commandId, acceptedAt),
        "user:" + userId);
  }

  public PreparedRecallCommand createRecall(long userId, String msgId) {
    String commandId = commandIdSupplier.get();
    Instant acceptedAt = Instant.now(clock);
    return new PreparedRecallCommand(
        MessageRecallCommand.builder()
            .commandId(commandId)
            .userId(userId)
            .msgId(msgId)
            .acceptedAt(acceptedAt)
            .build(),
        new CommandAcceptance(commandId, acceptedAt),
        msgId);
  }
}
