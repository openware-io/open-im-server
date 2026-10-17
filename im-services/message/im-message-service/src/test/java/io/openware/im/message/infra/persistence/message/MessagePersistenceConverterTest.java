package io.openware.im.message.infra.persistence.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgStatus;
import io.openware.common.enums.MsgType;
import io.openware.im.message.domain.message.model.MessageFavorite;
import io.openware.im.message.domain.message.readmodel.MessageView;
import io.openware.im.message.infra.persistence.message.po.MessageFavoritePo;
import io.openware.im.message.infra.persistence.message.po.MessagePo;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 消息与收藏的持久化映射：写入与回读一一对应，列表投影原样携带审计列，历史行保持全空。 */
class MessagePersistenceConverterTest {

  @Test
  void shouldRoundTripFavoriteSnapshotColumns() {
    LocalDateTime favoritedAt = LocalDateTime.now();
    LocalDateTime sentAt = favoritedAt.minusMinutes(5);
    MessageFavorite favorite = MessageFavorite.create(7L, "m1", "9", ChatType.PRIVATE,
        MsgType.IMAGE, "img-content", 9L, "sender", sentAt);

    MessageFavoritePo po = MessagePersistenceConverter.toPo(favorite);
    // 收藏时刻由适配器在写库时打戳（领域模型不再携带），这里显式模拟。
    po.setCreatedAt(favoritedAt);
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
    // 收藏时刻不在领域模型上：由只读投影 MessageFavoriteView 承载（standards/10 L266-279）。
    assertEquals(favoritedAt, MessagePersistenceConverter.toView(po).favoritedAt());
  }

  @Test
  void shouldKeepLegacyFavoriteWithoutSnapshot() {
    LocalDateTime favoritedAt = LocalDateTime.now();
    MessageFavorite legacy = MessageFavorite.create(7L, "m1", "9", ChatType.PRIVATE);

    MessageFavoritePo po = MessagePersistenceConverter.toPo(legacy);
    po.setCreatedAt(favoritedAt);
    assertNull(po.getMsgTypeSnapshot());
    assertNull(po.getContentSnapshot());
    assertNull(po.getFromUserIdSnapshot());
    assertNull(po.getSenderUsernameSnapshot());
    assertNull(po.getSentAtSnapshot());

    MessageFavorite restored = MessagePersistenceConverter.toDomain(po);
    assertFalse(restored.hasSnapshot(), "历史行（V13 之前）不得被判定为带快照");
    // 收藏时刻不在领域模型上：由只读投影 MessageFavoriteView 承载（standards/10 L266-279）。
    assertEquals(favoritedAt, MessagePersistenceConverter.toView(po).favoritedAt());
  }

  /**
   * 列表投影必须把响应要回显的列（含四个审计列）原样带给应用层：
   * 它们是投影存在的理由——审计列不进领域模型，但响应契约仍要读。
   */
  @Test
  void shouldCarryMessageColumnsAndAuditIntoTheListView() {
    LocalDateTime editedAt = LocalDateTime.of(2026, 7, 22, 8, 29);
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 8, 30);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 22, 8, 31);
    MessagePo po = new MessagePo();
    po.setId(11L);
    po.setMsgId("m-1");
    po.setConversationId("conv:7:9");
    po.setSeq(3L);
    po.setFromUserId(7L);
    po.setSenderUsername("sender");
    po.setToId("9");
    po.setChatType(ChatType.PRIVATE);
    po.setMsgType(MsgType.IMAGE);
    po.setContent("hello");
    po.setClientMsgId("client-1");
    po.setReplyMsgId("m-0");
    po.setAtUsers(List.of("9"));
    po.setMediaObjectIds(List.of("media-1"));
    po.setStatus(MsgStatus.SENT);
    po.setEdited(true);
    po.setEditedAt(editedAt);
    po.setCreatedBy(7L);
    po.setCreatedAt(createdAt);
    po.setUpdatedBy(9L);
    po.setUpdatedAt(updatedAt);

    MessageView view = MessagePersistenceConverter.toView(po);

    assertEquals(11L, view.id());
    assertEquals("m-1", view.msgId());
    assertEquals("conv:7:9", view.conversationId());
    assertEquals(3L, view.seq());
    assertEquals(7L, view.fromUserId());
    assertEquals("sender", view.senderUsername());
    assertEquals("9", view.toId());
    assertEquals(ChatType.PRIVATE, view.chatType());
    assertEquals(MsgType.IMAGE, view.msgType());
    assertEquals("hello", view.content());
    assertEquals("client-1", view.clientMsgId());
    assertEquals("m-0", view.replyMsgId());
    assertEquals(List.of("9"), view.atUsers());
    assertEquals(List.of("media-1"), view.mediaObjectIds());
    assertEquals(MsgStatus.SENT, view.status());
    assertTrue(view.edited());
    assertEquals(editedAt, view.editedAt());
    assertEquals(7L, view.createdBy());
    assertEquals(createdAt, view.createdAt());
    assertEquals(9L, view.updatedBy());
    assertEquals(updatedAt, view.updatedAt());

    // 历史行 media_object_ids 可为 null：投影必须与 Message.restore 一样回落为空列表，否则列表接口会 NPE。
    po.setMediaObjectIds(null);
    assertEquals(List.of(), MessagePersistenceConverter.toView(po).mediaObjectIds());
  }
}
