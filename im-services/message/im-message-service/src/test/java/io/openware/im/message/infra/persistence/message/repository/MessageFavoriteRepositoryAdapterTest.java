package io.openware.im.message.infra.persistence.message.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import io.openware.im.message.domain.message.model.MessageFavorite;
import io.openware.im.message.infra.persistence.message.mapper.MessageFavoriteMapper;
import io.openware.im.message.infra.persistence.message.po.MessageFavoritePo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MessageFavoriteRepositoryAdapterTest {

  @Test
  void shouldPersistSnapshotColumnsOnInsert() {
    MessageFavoriteMapper mapper = mock(MessageFavoriteMapper.class);
    when(mapper.insert(any(MessageFavoritePo.class))).thenReturn(1);
    LocalDateTime favoritedAt = LocalDateTime.now();
    LocalDateTime sentAt = favoritedAt.minusMinutes(2);
    MessageFavorite favorite = MessageFavorite.create(7L, "m1", "9", ChatType.GROUP, favoritedAt,
        MsgType.TEXT, "收藏正文", 9L, "sender", sentAt);

    assertTrue(new MessageFavoriteRepositoryAdapter(mapper).saveIfAbsent(favorite));

    ArgumentCaptor<MessageFavoritePo> captor = ArgumentCaptor.forClass(MessageFavoritePo.class);
    verify(mapper).insert(captor.capture());
    MessageFavoritePo po = captor.getValue();
    assertEquals(7L, po.getUserId());
    assertEquals("m1", po.getMsgId());
    assertEquals(MsgType.TEXT, po.getMsgTypeSnapshot());
    assertEquals("收藏正文", po.getContentSnapshot());
    assertEquals(9L, po.getFromUserIdSnapshot());
    assertEquals("sender", po.getSenderUsernameSnapshot());
    assertEquals(sentAt, po.getSentAtSnapshot());
  }

  @Test
  void shouldReportNotCreatedOnDuplicateFavorite() {
    MessageFavoriteMapper mapper = mock(MessageFavoriteMapper.class);
    when(mapper.insert(any(MessageFavoritePo.class))).thenThrow(new DuplicateKeyException("uk_user_msg"));

    assertFalse(new MessageFavoriteRepositoryAdapter(mapper).saveIfAbsent(
        MessageFavorite.create(7L, "m1", "9", ChatType.PRIVATE, LocalDateTime.now())),
        "命中 (user_id, msg_id) 唯一约束时应幂等返回 false");
  }
}
