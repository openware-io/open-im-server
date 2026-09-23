package io.openware.im.message.api.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgStatus;
import io.openware.common.enums.MsgType;
import io.openware.im.message.application.result.DeleteMessageResult;
import io.openware.im.message.application.result.MessageResult;
import io.openware.im.message.application.result.SearchMessagesResult;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class MessageApiConverterTest {
  @Test
  void shouldPreserveSearchAndDeleteResponseFields() {
    MessageResult message = new MessageResult(1L, "m-1", "private:7:9", 2L, 7L, "sender", "9",
        ChatType.PRIVATE, MsgType.TEXT, "hello", "client-1", null, List.of("9"), MsgStatus.SENT, 0L,
        LocalDateTime.parse("2026-07-22T00:00:00"), 0L, LocalDateTime.parse("2026-07-22T00:00:00"));

    var search = MessageApiConverter.toResponse(new SearchMessagesResult(List.of(message), 1L, 1, 20));
    var deletion = MessageApiConverter.toResponse(new DeleteMessageResult(true, "m-1", ChatType.PRIVATE, "9", 7L));

    assertEquals("m-1", search.items().getFirst().msgId());
    assertEquals(1L, search.total());
    assertEquals(20, search.pageSize());
    assertEquals(true, deletion.ok());
    assertEquals(7L, deletion.fromUserId());
  }

  @Test
  void shouldSerializeMessageTimesAsUtcInstants() throws Exception {
    MessageResult message = new MessageResult(1L, "m-1", "private:7:9", 2L, 7L, "sender", "9",
        ChatType.PRIVATE, MsgType.TEXT, "hello", null, null, List.of(), MsgStatus.SENT, 0L,
        LocalDateTime.of(2026, 7, 22, 8, 30), 0L, LocalDateTime.of(2026, 7, 22, 8, 30));

    var response = MessageApiConverter.toResponse(message);
    var json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .writeValueAsString(response);

    assertEquals("2026-07-22T08:30:00Z", response.createdAt().toString());
    assertEquals(true, json.contains("\"createdAt\":\"2026-07-22T08:30:00Z\""));
    assertEquals(true, json.contains("\"updatedAt\":\"2026-07-22T08:30:00Z\""));
  }
}
