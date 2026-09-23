package io.openware.im.message.domain.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import io.openware.im.message.domain.message.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {
  @Test
  void shouldMarkEditedAndUpdateContentImmutably() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 10, 0, 0);
    Message message = Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), createdAt);

    Message edited = message.edited("hello v2", 7L, createdAt.plusSeconds(30));

    assertEquals("hello v2", edited.getContent());
    assertTrue(edited.isEdited());
    assertEquals(createdAt.plusSeconds(30), edited.getEditedAt());
    assertEquals(7L, edited.getUpdatedBy());
    assertEquals(createdAt.plusSeconds(30), edited.getUpdatedAt());
    // 领域实体不可变：原对象保持原正文与未编辑标记。
    assertEquals("hello", message.getContent());
    assertFalse(message.isEdited());
  }

  @Test
  void editableWindowIsTwoMinutesFromSend() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 10, 0, 0);
    Message message = Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), createdAt);

    assertTrue(message.editableAt(createdAt.plusSeconds(119)));
    assertFalse(message.editableAt(createdAt.plusSeconds(121)));
  }
}
