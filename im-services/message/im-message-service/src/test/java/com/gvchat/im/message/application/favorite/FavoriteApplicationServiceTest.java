package com.gvchat.im.message.application.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.message.application.favorite.command.AddFavoriteCommand;
import com.gvchat.im.message.application.favorite.command.AddFavoritesBatchCommand;
import com.gvchat.im.message.application.favorite.query.FavoriteListQuery;
import com.gvchat.im.message.application.favorite.result.FavoriteBatchResult;
import com.gvchat.im.message.application.favorite.result.FavoriteSourceResult;
import com.gvchat.im.message.application.favorite.result.FavoriteSourceState;
import com.gvchat.im.message.domain.message.model.Message;
import com.gvchat.im.message.domain.message.model.MessageFavorite;
import com.gvchat.im.message.domain.message.port.ChannelMembershipPort;
import com.gvchat.im.message.domain.message.port.FriendRelationPort;
import com.gvchat.im.message.domain.message.port.GroupMembershipPort;
import com.gvchat.im.message.domain.message.repository.MessageFavoriteRepository;
import com.gvchat.im.message.domain.message.repository.MessageRepository;
import com.gvchat.im.message.domain.secretgroupmessage.port.SecretGroupChatPort;
import com.gvchat.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import com.gvchat.im.message.domain.secretmessage.model.SecretMessage;
import com.gvchat.im.message.domain.secretmessage.port.SecretChatParticipantPort;
import com.gvchat.im.message.domain.secretmessage.repository.SecretMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FavoriteApplicationServiceTest {

  @Test
  void favoriteIsIdempotentForSameUserAndMessage() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(privateMessage("m-1", 7L, 9L)));
    FavoriteApplicationService service = service(favorites, messages);

    service.favorite(new AddFavoriteCommand(7L, "m-1", "9", ChatType.PRIVATE));
    service.favorite(new AddFavoriteCommand(7L, "m-1", "9", ChatType.PRIVATE));

    assertEquals(1, favorites.items.size(), "重复收藏不应重复插入");
  }

  @Test
  void unfavoriteIsIdempotentEvenWhenFavoriteMissing() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(privateMessage("m-1", 7L, 9L)));
    FavoriteApplicationService service = service(favorites, messages);

    service.favorite(new AddFavoriteCommand(7L, "m-1", "9", ChatType.PRIVATE));
    service.unfavorite(7L, "m-1");
    service.unfavorite(7L, "m-1");
    service.unfavorite(7L, "m-1");

    assertEquals(0, favorites.items.size(), "取消不存在的收藏应静默成功（幂等）");
  }

  @Test
  void favoriteRejectsMessageThatDoesNotExist() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("ghost")).thenReturn(Optional.empty());
    FavoriteApplicationService service = service(favorites, messages);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.favorite(new AddFavoriteCommand(7L, "ghost", "9", ChatType.PRIVATE)));

    assertEquals(HttpStatusCodes.NOT_FOUND, ex.getStatus());
    assertEquals(0, favorites.items.size(), "消息不存在时不应落库");
  }

  @Test
  void favoriteRejectsMessageUserCannotAccess() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    // 群消息，但当前用户已不是群成员 → 无权收藏（IDOR 写时校验）。
    when(messages.findByMsgId("m-group")).thenReturn(Optional.of(groupMessage("m-group", 100L, 9L)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(false), channelMembershipPort(false));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.favorite(new AddFavoriteCommand(7L, "m-group", "100", ChatType.GROUP)));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
    assertEquals(0, favorites.items.size(), "无权访问时不应落库");
  }

  @Test
  void favoriteWritesSnapshotOfAuthoritativeMessage() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    Message message = privateMessage("m-1", 7L, 9L);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(message));
    FavoriteApplicationService service = service(favorites, messages);

    service.favorite(new AddFavoriteCommand(7L, "m-1", "9", ChatType.PRIVATE));

    MessageFavorite saved = favorites.items.get(0);
    assertTrue(saved.hasSnapshot(), "收藏成功时必须写入内容快照");
    assertEquals(MsgType.TEXT, saved.getMsgTypeSnapshot());
    assertEquals("hello", saved.getContentSnapshot());
    assertEquals(7L, saved.getFromUserIdSnapshot());
    assertEquals("sender", saved.getSenderUsernameSnapshot());
    assertEquals(message.getCreatedAt(), saved.getSentAtSnapshot());
  }

  @Test
  void favoriteDoesNotWriteSnapshotForSecretChat() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    SecretMessageRepository secretMessages = mock(SecretMessageRepository.class);
    when(secretMessages.findByMsgId(7L, "m-secret"))
        .thenReturn(Optional.of(SecretMessage.post(7L, "m-secret", 1L, "cipher", 1L, 1L, LocalDateTime.now())));
    SecretChatParticipantPort participants = mock(SecretChatParticipantPort.class);
    when(participants.findParticipants(7L)).thenReturn(List.of(1L, 2L));
    FavoriteApplicationService service = new FavoriteApplicationService(favorites, mock(MessageRepository.class),
        (userId, peerUserId) -> true, groupMembershipPort(true), channelMembershipPort(false),
        secretMessages, participants, mock(SecretGroupMessageRepository.class), mock(SecretGroupChatPort.class));

    service.favorite(new AddFavoriteCommand(2L, "m-secret", "7", ChatType.SECRET));

    assertEquals(1, favorites.items.size(), "密聊参与方应能收藏密聊消息");
    assertFalse(favorites.items.get(0).hasSnapshot(),
        "密聊服务端只存密文且设计为定时销毁，收藏刻意不落正文快照");
  }

  @Test
  void favoriteBatchIsIdempotentOnRepeat() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(groupMessage("m-1", 100L, 9L)));
    when(messages.findByMsgId("m-2")).thenReturn(Optional.of(groupMessage("m-2", 100L, 9L)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPortOf(Set.of(100L)), channelMembershipPort(false));

    FavoriteBatchResult first = service.favoriteBatch(
        new AddFavoritesBatchCommand(7L, "100", ChatType.GROUP, List.of("m-1", "m-2")));
    FavoriteBatchResult second = service.favoriteBatch(
        new AddFavoritesBatchCommand(7L, "100", ChatType.GROUP, List.of("m-1", "m-2")));

    assertEquals(2, first.created());
    assertEquals(0, first.skipped());
    assertTrue(first.items().stream().allMatch(FavoriteBatchResult.Item::created));
    assertEquals(0, second.created(), "重复批量收藏应全部命中幂等约束");
    assertEquals(2, second.skipped());
    assertFalse(second.items().stream().anyMatch(FavoriteBatchResult.Item::created));
    assertEquals(2, favorites.items.size(), "重复批量收藏不应重复插入");
  }

  @Test
  void favoriteBatchKeepsGoingWhenOneMessageIsUnauthorizedOrMissing() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-ok")).thenReturn(Optional.of(groupMessage("m-ok", 100L, 9L)));
    // 该消息真实归属群 200，而当前用户不是群 200 成员 → 单条无权访问。
    when(messages.findByMsgId("m-denied")).thenReturn(Optional.of(groupMessage("m-denied", 200L, 9L)));
    when(messages.findByMsgId("m-ghost")).thenReturn(Optional.empty());
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPortOf(Set.of(100L)), channelMembershipPort(false));

    FavoriteBatchResult result = service.favoriteBatch(
        new AddFavoritesBatchCommand(7L, "100", ChatType.GROUP, List.of("m-ok", "m-denied", "m-ghost")));

    assertEquals(1, result.created(), "只有可访问的消息被收藏");
    assertEquals(2, result.skipped(), "无权访问与不存在的消息各记一次未创建");
    assertEquals(3, result.items().size());
    assertEquals("m-ok", result.items().get(0).messageId());
    assertTrue(result.items().get(0).created());
    assertFalse(result.items().get(1).created(), "无权访问的单条不应中止整批");
    assertFalse(result.items().get(2).created());
    assertEquals(1, favorites.items.size(), "单条失败不应回滚已成功的收藏");
    assertEquals("m-ok", favorites.items.get(0).getMsgId());
  }

  @Test
  void favoriteBatchItemsMirrorRequestOrderAndCount() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(privateMessage("m-1", 7L, 9L)));
    FavoriteApplicationService service = service(favorites, messages);

    // 同一 msgId 重复出现：第一条新建，第二条命中幂等；items 与请求一一对应。
    FavoriteBatchResult result = service.favoriteBatch(
        new AddFavoritesBatchCommand(7L, "9", ChatType.PRIVATE, List.of("m-1", "m-1")));

    assertEquals(1, result.created());
    assertEquals(1, result.skipped());
    assertEquals(2, result.items().size());
    assertTrue(result.items().get(0).created());
    assertFalse(result.items().get(1).created());
    assertEquals("m-1", result.items().get(1).messageId());
  }

  @Test
  void listFiltersByOwnerPaginatesAndFallsBackToPlaceholder() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-1", "9", ChatType.PRIVATE, now.minusSeconds(2)));
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-2", "9", ChatType.PRIVATE, now.minusSeconds(1)));
    favorites.saveIfAbsent(MessageFavorite.create(8L, "m-3", "9", ChatType.PRIVATE, now));

    MessageRepository messages = mock(MessageRepository.class);
    // 仅 m-2 仍可回查（m-1 已撤回/删除）。
    when(messages.findByMsgIds(anyList())).thenReturn(List.of(
        Message.create("m-2", "conv:7:9", 2L, 7L, "sender", "9", ChatType.PRIVATE, MsgType.TEXT, "hello",
            null, null, List.of(), now)));
    FavoriteApplicationService service = service(favorites, messages);

    // 分页：第 1 页 1 条，总数 2（仅用户 7 自己的收藏，不含用户 8 的 m-3）。
    var firstPage = service.listFavorites(new FavoriteListQuery(7L, 1, 1));
    assertEquals(2, firstPage.total());
    assertEquals(1, firstPage.items().size());
    assertEquals("m-2", firstPage.items().get(0).msgId(), "按收藏时间倒序，最新收藏在前");
    assertEquals(MsgType.TEXT, firstPage.items().get(0).msgType());

    // 全量：m-1 回查不到且无快照（历史行）→ 消息侧字段置空（占位）。
    var fullPage = service.listFavorites(new FavoriteListQuery(7L, 1, 10));
    assertEquals(2, fullPage.items().size());
    assertEquals("m-1", fullPage.items().get(1).msgId());
    assertNull(fullPage.items().get(1).msgType(), "已删除消息回查不到时 msgType 应为 null（占位）");
    assertNull(fullPage.items().get(1).senderUsername());
  }

  @Test
  void listKeepsPlaceholderForLegacyFavoriteWithoutSnapshotWhenUserLostAccess() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    // 历史行（V13 之前落库）：快照为空。用户 7 曾收藏一条群消息，随后被移出群。
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-secret", "100", ChatType.GROUP, now));

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgIds(anyList())).thenReturn(List.of(
        groupMessage("m-secret", 100L, 9L)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(false), channelMembershipPort(false));

    var page = service.listFavorites(new FavoriteListQuery(7L, 1, 10));

    assertEquals(1, page.items().size());
    var item = page.items().get(0);
    assertEquals("m-secret", item.msgId());
    assertNull(item.msgType(), "无快照的历史行在无权访问时 msgType 应置空（占位）");
    assertNull(item.content(), "无快照的历史行不应泄露正文");
    assertNull(item.senderId(), "无快照的历史行不应泄露发送者");
    assertNull(item.senderUsername());
    assertNull(item.createdAt());
  }

  @Test
  void listStillShowsContentWhenUserCanAccess() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-ok", "100", ChatType.GROUP, now));

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgIds(anyList())).thenReturn(List.of(
        groupMessage("m-ok", 100L, 9L)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(true), channelMembershipPort(false));

    var page = service.listFavorites(new FavoriteListQuery(7L, 1, 10));

    assertEquals(1, page.items().size());
    assertEquals("m-ok", page.items().get(0).msgId());
    assertEquals(MsgType.TEXT, page.items().get(0).msgType());
    assertEquals("group secret", page.items().get(0).content());
  }

  @Test
  void listPrefersLiveMessageOverSnapshot() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-edited", "100", ChatType.GROUP, now,
        MsgType.TEXT, "收藏时的旧内容", 9L, "sender", now.minusMinutes(5)));

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgIds(anyList())).thenReturn(List.of(
        Message.create("m-edited", "conv:group:100", 2L, 9L, "sender", "100", ChatType.GROUP, MsgType.TEXT,
            "编辑后的最新内容", null, null, List.of(), now)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(true), channelMembershipPort(false));

    var item = service.listFavorites(new FavoriteListQuery(7L, 1, 10)).items().get(0);

    assertEquals("编辑后的最新内容", item.content(), "实时消息优先，必须看到编辑后的最新内容");
  }

  @Test
  void listFallsBackToSnapshotAfterMessageGone() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-gone", "9", ChatType.PRIVATE, now,
        MsgType.TEXT, "收藏时的内容", 9L, "sender", now.minusMinutes(3)));

    MessageRepository messages = mock(MessageRepository.class);
    // 权威消息已硬删除（撤回/删除）。
    when(messages.findByMsgIds(anyList())).thenReturn(List.of());
    FavoriteApplicationService service = service(favorites, messages);

    var item = service.listFavorites(new FavoriteListQuery(7L, 1, 10)).items().get(0);

    assertEquals(MsgType.TEXT, item.msgType(), "消息删除后应回落到快照的类型");
    assertEquals("收藏时的内容", item.content(), "消息删除后应回落到快照的正文");
    assertEquals(9L, item.senderId());
    assertEquals("sender", item.senderUsername());
    assertEquals(now.minusMinutes(3), item.createdAt(), "快照里存的是原消息发送时间");
  }

  @Test
  void listFallsBackToSnapshotAfterAccessLost() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    LocalDateTime now = LocalDateTime.now();
    favorites.saveIfAbsent(MessageFavorite.create(7L, "m-left", "100", ChatType.GROUP, now,
        MsgType.TEXT, "收藏时的内容", 9L, "sender", now.minusMinutes(1)));

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgIds(anyList())).thenReturn(List.of(groupMessage("m-left", 100L, 9L)));
    FavoriteApplicationService service = service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(false), channelMembershipPort(false));

    var item = service.listFavorites(new FavoriteListQuery(7L, 1, 10)).items().get(0);

    assertEquals("收藏时的内容", item.content(), "已无权访问时回落收藏时快照，保证收藏内容永远可读");
    assertEquals(MsgType.TEXT, item.msgType());
  }

  @Test
  void sourceReturnsAvailableWhenMessageStillAccessible() {
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(privateMessage("m-1", 7L, 9L)));
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages);

    FavoriteSourceResult result = service.locateSource(7L, "m-1");

    assertEquals(FavoriteSourceState.AVAILABLE, result.state());
    assertEquals("conv:7:9", result.conversationId());
    assertEquals("m-1", result.messageId());
  }

  @Test
  void sourceReturnsMessageDeletedWhenAuthoritativeMessageGone() {
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-gone")).thenReturn(Optional.empty());
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages);

    FavoriteSourceResult result = service.locateSource(7L, "m-gone");

    assertEquals(FavoriteSourceState.MESSAGE_DELETED, result.state());
    assertNull(result.conversationId());
    assertNull(result.messageId());
  }

  @Test
  void sourceReturnsConversationUnavailableWhenPrivateFriendshipLost() {
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-1")).thenReturn(Optional.of(privateMessage("m-1", 7L, 9L)));
    // 消息存在、双方曾是会话参与方，但好友关系已解除。
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages,
        (userId, peerUserId) -> false, groupMembershipPort(true), channelMembershipPort(false));

    FavoriteSourceResult result = service.locateSource(7L, "m-1");

    assertEquals(FavoriteSourceState.CONVERSATION_UNAVAILABLE, result.state());
    assertNull(result.conversationId());
    assertNull(result.messageId());
  }

  @Test
  void sourceReturnsConversationUnavailableWhenRemovedFromGroup() {
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-group")).thenReturn(Optional.of(groupMessage("m-group", 100L, 9L)));
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages,
        (userId, peerUserId) -> true, groupMembershipPort(false), channelMembershipPort(false));

    FavoriteSourceResult result = service.locateSource(7L, "m-group");

    assertEquals(FavoriteSourceState.CONVERSATION_UNAVAILABLE, result.state());
  }

  @Test
  void sourceReturnsNoPermissionForPrivateMessageUserIsNotPartOf() {
    MessageRepository messages = mock(MessageRepository.class);
    // 私聊消息属于用户 8 与 9，与当前用户 7 无关：明确拒绝，不归因为「会话不可用」。
    when(messages.findByMsgId("m-other")).thenReturn(Optional.of(privateMessage("m-other", 8L, 9L)));
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages,
        (userId, peerUserId) -> true, groupMembershipPort(true), channelMembershipPort(false));

    FavoriteSourceResult result = service.locateSource(7L, "m-other");

    assertEquals(FavoriteSourceState.NO_PERMISSION, result.state());
  }

  @Test
  void sourceReturnsNoPermissionForSecretChatMessage() {
    MessageRepository messages = mock(MessageRepository.class);
    Message secret = Message.create("m-secret-chat", "conv:secret:7", 1L, 1L, "sender", "7", ChatType.SECRET,
        MsgType.TEXT, "cipher", null, null, List.of(), LocalDateTime.now());
    when(messages.findByMsgId("m-secret-chat")).thenReturn(Optional.of(secret));
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages);

    FavoriteSourceResult result = service.locateSource(7L, "m-secret-chat");

    assertEquals(FavoriteSourceState.NO_PERMISSION, result.state(), "不走 msg_message 权威通道的会话类型明确拒绝");
  }

  @Test
  void sourceReturnsLookupUnavailableWhenLookupThrows() {
    MessageRepository messages = mock(MessageRepository.class);
    when(messages.findByMsgId("m-db-down")).thenThrow(new IllegalStateException("db down"));
    FavoriteApplicationService service = service(new InMemoryMessageFavoriteRepository(), messages);

    FavoriteSourceResult result = service.locateSource(7L, "m-db-down");

    assertEquals(FavoriteSourceState.LOOKUP_UNAVAILABLE, result.state(), "查询抛异常必须 fail closed，绝不猜测");
    assertNull(result.conversationId());
    assertNull(result.messageId());
  }

  @Test
  void favoriteAllowsSecretChatParticipant() {
    InMemoryMessageFavoriteRepository favorites = new InMemoryMessageFavoriteRepository();
    SecretMessageRepository secretMessages = mock(SecretMessageRepository.class);
    when(secretMessages.findByMsgId(7L, "m-secret"))
        .thenReturn(Optional.of(SecretMessage.post(7L, "m-secret", 1L, "cipher", 1L, 1L, LocalDateTime.now())));
    SecretChatParticipantPort participants = mock(SecretChatParticipantPort.class);
    when(participants.findParticipants(7L)).thenReturn(List.of(1L, 2L));
    FavoriteApplicationService service = new FavoriteApplicationService(favorites, mock(MessageRepository.class),
        (userId, peerUserId) -> true, groupMembershipPort(true), channelMembershipPort(false),
        secretMessages, participants, mock(SecretGroupMessageRepository.class), mock(SecretGroupChatPort.class));

    service.favorite(new AddFavoriteCommand(2L, "m-secret", "7", ChatType.SECRET));

    assertEquals(1, favorites.items.size(), "密聊参与方应能收藏密聊消息");
  }

  private FavoriteApplicationService service(MessageFavoriteRepository favorites, MessageRepository messages) {
    return service(favorites, messages, (userId, peerUserId) -> true,
        groupMembershipPort(true), channelMembershipPort(false));
  }

  private FavoriteApplicationService service(MessageFavoriteRepository favorites, MessageRepository messages,
      FriendRelationPort friendRelationPort, GroupMembershipPort groupMembershipPort,
      ChannelMembershipPort channelMembershipPort) {
    return new FavoriteApplicationService(favorites, messages, friendRelationPort, groupMembershipPort,
        channelMembershipPort, mock(SecretMessageRepository.class), mock(SecretChatParticipantPort.class),
        mock(SecretGroupMessageRepository.class), mock(SecretGroupChatPort.class));
  }

  private GroupMembershipPort groupMembershipPort(boolean member) {
    return groupMembershipPortOf(member ? Set.of(100L) : Set.of());
  }

  /** 按群 id 精确判定成员关系，用于「同批中部分消息归属别的群」的场景。 */
  private GroupMembershipPort groupMembershipPortOf(Set<Long> memberGroups) {
    return new GroupMembershipPort() {
      @Override public boolean isMember(long groupId, long userId) { return memberGroups.contains(groupId); }
      @Override public boolean isOwnerOrAdmin(long groupId, long userId) { return memberGroups.contains(groupId); }
      @Override public List<Long> findMemberUserIds(long groupId) { return List.of(); }
    };
  }

  private ChannelMembershipPort channelMembershipPort(boolean subscribed) {
    return new ChannelMembershipPort() {
      @Override public boolean isOwner(long channelId, long userId) { return subscribed; }
      @Override public boolean isSubscribed(long channelId, long userId) { return subscribed; }
      @Override public List<Long> listSubscriberUserIds(long channelId) { return List.of(); }
    };
  }

  private Message privateMessage(String msgId, long fromUserId, long toUserId) {
    return Message.create(msgId, "conv:" + fromUserId + ":" + toUserId, 1L, fromUserId, "sender",
        String.valueOf(toUserId), ChatType.PRIVATE, MsgType.TEXT, "hello", null, null, List.of(),
        LocalDateTime.now());
  }

  private Message groupMessage(String msgId, long groupId, long fromUserId) {
    return Message.create(msgId, "conv:group:" + groupId, 1L, fromUserId, "sender", String.valueOf(groupId),
        ChatType.GROUP, MsgType.TEXT, "group secret", null, null, List.of(), LocalDateTime.now());
  }

  private static final class InMemoryMessageFavoriteRepository implements MessageFavoriteRepository {
    private final List<MessageFavorite> items = new ArrayList<>();

    @Override
    public boolean saveIfAbsent(MessageFavorite favorite) {
      boolean exists = items.stream().anyMatch(item -> item.getUserId() == favorite.getUserId()
          && item.getMsgId().equals(favorite.getMsgId()));
      if (exists) {
        return false;
      }
      items.add(favorite);
      return true;
    }

    @Override
    public void deleteByUserIdAndMsgId(long userId, String msgId) {
      items.removeIf(item -> item.getUserId() == userId && item.getMsgId().equals(msgId));
    }

    @Override
    public void deleteByMsgId(String msgId) {
      items.removeIf(item -> item.getMsgId().equals(msgId));
    }

    @Override
    public FavoritePage findByUserId(long userId, int page, int pageSize) {
      List<MessageFavorite> own = items.stream().filter(item -> item.getUserId() == userId)
          .sorted(Comparator.comparing(MessageFavorite::getCreatedAt).reversed()).toList();
      int from = Math.min((page - 1) * pageSize, own.size());
      int to = Math.min(from + pageSize, own.size());
      return new FavoritePage(own.subList(from, to), own.size());
    }
  }
}
