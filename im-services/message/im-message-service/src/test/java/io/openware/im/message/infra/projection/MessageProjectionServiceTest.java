package io.openware.im.message.infra.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.im.message.domain.message.event.MessageEditedEvent;
import io.openware.im.message.domain.message.port.UnreadCountProjectionPort;
import io.openware.protocol.mq.event.MessageStoredEvent;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MessageProjectionServiceTest {
  @Test
  void shouldInvalidateUnreadCacheForEveryReplayInsteadOfIncrementingIt() {
    RecordingUnreadProjection projection = new RecordingUnreadProjection();
    MessageProjectionService service = new MessageProjectionService(mock(MongoTemplate.class), projection, 30);
    MessageStoredEvent event = MessageStoredEvent.builder()
        .eventId("message-1")
        .msgId("message-1")
        .conversationId("private:7:9")
        .seq(1L)
        .senderId(7L)
        .senderUsername("sender")
        .chatType("private")
        .toId("9")
        .msgType("text")
        .content("hello")
        .createdAt(Instant.parse("2026-07-27T00:00:00Z"))
        .build();

    service.project(event);
    service.project(event);

    assertThat(projection.invalidations).isEqualTo(2);
  }

  @Test
  void shouldInvalidateUnreadForAllRecipients() {
    RecordingUnreadProjection projection = new RecordingUnreadProjection();
    MessageProjectionService service = new MessageProjectionService(mock(MongoTemplate.class), projection, 30);
    MessageStoredEvent event = MessageStoredEvent.builder()
        .eventId("message-1")
        .msgId("message-1")
        .conversationId("group:100")
        .seq(1L)
        .senderId(7L)
        .chatType("group")
        .toId("100")
        .msgType("text")
        .content("hello")
        .recipientUserIds(List.of(9L, 11L))
        .createdAt(Instant.parse("2026-07-27T00:00:00Z"))
        .build();

    service.project(event);

    assertThat(projection.invalidatedUserIds).containsExactly(9L, 11L);
  }

  @Test
  void shouldUpdateHotProjectionOnEdit() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    HotMessageDocument existing = new HotMessageDocument();
    existing.setId("message-1");
    existing.setContent("hello");
    when(mongo.findById("message-1", HotMessageDocument.class)).thenReturn(existing);
    MessageProjectionService service = new MessageProjectionService(mongo, new RecordingUnreadProjection(), 30);
    MessageEditedEvent event = new MessageEditedEvent("e-1", "message-1", "private:7:9", 1L, 7L, "private", "9",
        "hello edited", List.of(), Instant.parse("2026-07-27T00:01:00Z"));

    service.projectEdit(event);

    assertThat(existing.getContent()).isEqualTo("hello edited");
    assertThat(existing.isEdited()).isTrue();
    assertThat(existing.getEditedAt()).isEqualTo(Instant.parse("2026-07-27T00:01:00Z"));
  }

  private static final class RecordingUnreadProjection implements UnreadCountProjectionPort {
    private int invalidations;
    private final List<Long> invalidatedUserIds = new java.util.ArrayList<>();

    @Override public OptionalLong find(long userId) { return OptionalLong.empty(); }
    @Override public void replace(long userId, long count) { }
    @Override public void invalidate(long userId) { invalidations++; invalidatedUserIds.add(userId); }
  }
}
