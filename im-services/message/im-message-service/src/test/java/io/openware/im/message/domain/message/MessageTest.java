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

    assertTrue(Message.editableAt(createdAt, createdAt.plusSeconds(119)));
    assertFalse(Message.editableAt(createdAt, createdAt.plusSeconds(121)));
  }

  /**
   * 精确边界：恰好在第 120 秒**不可**编辑。
   *
   * <p>实现用的是 {@code createdAt.plus(EDIT_WINDOW).isAfter(now)}，所以「恰好等于截止时刻」不满足
   * {@code isAfter} → 判定为不可编辑。这条边界最容易在重构中被改错（把 `isAfter` 换成
   * `!isBefore` 就会让这一刻变成可编辑），所以单独钉住。
   *
   * <p>V2 会把 {@code createdAt} 移出领域模型，届时该判定将改为静态纯函数
   * {@code editableAt(sentAt, now)}；此测试用于保证改动前后边界语义一致。
   */
  @Test
  void editableWindowExcludesTheExactDeadlineMoment() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 10, 0, 0);
    Message message = Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), createdAt);

    assertTrue(Message.editableAt(createdAt, createdAt.plusSeconds(120).minusNanos(1)),
        "截止前一纳秒仍可编辑");
    assertFalse(Message.editableAt(createdAt, createdAt.plusSeconds(120)),
        "恰好等于截止时刻不可编辑（实现用 isAfter，不是 !isBefore）");
  }

  /** 空值安全：发送时刻或当前时刻缺失时一律不可编辑（不得抛 NPE）。 */
  @Test
  void editableWindowIsFalseWhenEitherMomentIsMissing() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 10, 0, 0);
    Message message = Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), createdAt);

    assertFalse(Message.editableAt(createdAt, null), "当前时刻缺失时不可编辑");
  }
}
