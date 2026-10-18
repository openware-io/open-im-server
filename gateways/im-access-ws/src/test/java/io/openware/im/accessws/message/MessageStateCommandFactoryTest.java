package io.openware.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageStateCommandFactoryTest {
  @Test
  void shouldCreateReadCommandWithConversationShardingKey() {
    MessageStateCommandFactory factory = new MessageStateCommandFactory(
        () -> "command-read-1", Clock.fixed(Instant.parse("2026-07-22T03:12:45Z"), ZoneOffset.UTC));

    PreparedReadCommand prepared = factory.createRead(9L, "conv:private:7:9", List.of("m-1", "m-2"));

    assertEquals("command-read-1", prepared.command().getCommandId());
    assertEquals("conv:private:7:9", prepared.command().getConversationId());
    assertEquals(9L, prepared.command().getUserId());
    assertEquals(List.of("m-1", "m-2"), prepared.command().getMsgIds());
    assertEquals("conv:private:7:9", prepared.shardingKey());
    assertEquals(Instant.parse("2026-07-22T03:12:45Z"), prepared.acceptance().acceptedAt());
  }

  @Test
  void shouldCreateRecallCommandWithConversationShardingKey() {
    MessageStateCommandFactory factory = new MessageStateCommandFactory(
        () -> "command-recall-1", Clock.fixed(Instant.parse("2026-07-22T04:12:45Z"), ZoneOffset.UTC));

    PreparedRecallCommand prepared = factory.createRecall(9L, "conv:private:7:9", "m-1");

    assertEquals("command-recall-1", prepared.command().getCommandId());
    assertEquals("conv:private:7:9", prepared.command().getConversationId());
    assertEquals(9L, prepared.command().getUserId());
    assertEquals("m-1", prepared.command().getMsgId());
    assertEquals("conv:private:7:9", prepared.shardingKey());
    assertEquals(Instant.parse("2026-07-22T04:12:45Z"), prepared.acceptance().acceptedAt());
  }
}
