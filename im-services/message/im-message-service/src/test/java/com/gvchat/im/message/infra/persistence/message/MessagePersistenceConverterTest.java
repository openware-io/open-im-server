package com.gvchat.im.message.infra.persistence.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import com.gvchat.im.message.domain.message.model.MessageFavorite;
import com.gvchat.im.message.infra.persistence.message.po.MessageFavoritePo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 收藏快照列的持久化映射：写入与回读必须一一对应，历史行保持全空。 */
class MessagePersistenceConverterTest {

  @Test
  void shouldRoundTripFavoriteSnapshotColumns() {
    LocalDateTime favoritedAt = LocalDateTime.now();
    LocalDateTime sentAt = favoritedAt.minusMinutes(5);
    MessageFavorite favorite = MessageFavorite.create(7L, "m1", "9", ChatType.PRIVATE, favoritedAt,
        MsgType.IMAGE, "img-content", 9L, "sender", sentAt);

    MessageFavoritePo po = MessagePersistenceConverter.toPo(favorite);
    assertEquals(MsgType.IMAGE, po.getMsgTypeSnapshot());
    assertEquals("img-content", po.getContentSnapshot());
    assertEquals(9L, po.getFromUserIdSnapshot());
    assertEquals("sender", po.getSenderUsernameSnapshot());
    assertEquals(sentAt, po.getSentAtSnapshot());

    MessageFavorite restored = MessagePersistenceConverter.toDomain(po);
    assertTrue(restored.hasSnapshot());
    assertEquals(MsgType.IMAGE, restored.getMsgTypeSnapshot());
    assertEquals("img-content", restored.getContentSnapshot());
    assertEquals(9L, restored.getFromUserIdSnapshot());
    assertEquals("sender", restored.getSenderUsernameSnapshot());
    assertEquals(sentAt, restored.getSentAtSnapshot());
    assertEquals(favoritedAt, restored.getCreatedAt());
  }

  @Test
  void shouldKeepLegacyFavoriteWithoutSnapshot() {
    LocalDateTime favoritedAt = LocalDateTime.now();
    MessageFavorite legacy = MessageFavorite.create(7L, "m1", "9", ChatType.PRIVATE, favoritedAt);

    MessageFavoritePo po = MessagePersistenceConverter.toPo(legacy);
    assertNull(po.getMsgTypeSnapshot());
    assertNull(po.getContentSnapshot());
    assertNull(po.getFromUserIdSnapshot());
    assertNull(po.getSenderUsernameSnapshot());
    assertNull(po.getSentAtSnapshot());

    MessageFavorite restored = MessagePersistenceConverter.toDomain(po);
    assertFalse(restored.hasSnapshot(), "历史行（V13 之前）不得被判定为带快照");
    assertEquals(favoritedAt, restored.getCreatedAt());
  }
}
