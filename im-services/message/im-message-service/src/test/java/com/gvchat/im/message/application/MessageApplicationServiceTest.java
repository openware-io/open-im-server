package com.gvchat.im.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.message.application.command.ClearPrivateChatCommand;
import com.gvchat.im.message.application.command.DeleteMessageCommand;
import com.gvchat.im.message.application.command.EditMessageCommand;
import com.gvchat.im.message.application.command.MarkMessagesReadCommand;
import com.gvchat.im.message.application.command.StoreMessageCommand;
import com.gvchat.im.message.application.query.SearchMessagesQuery;
import com.gvchat.im.message.application.query.MessageSyncQuery;
import com.gvchat.im.message.application.result.ConversationUnreadResult;
import com.gvchat.im.message.domain.message.event.MessageEditedEvent;
import com.gvchat.im.message.domain.message.model.Message;
import com.gvchat.im.message.domain.message.model.Mention;
import com.gvchat.im.message.domain.message.model.MessageOutbox;
import com.gvchat.im.message.domain.message.model.MessageReadStatus;
import com.gvchat.im.message.domain.message.port.ConversationSequencePort;
import com.gvchat.im.message.domain.message.port.FriendRelationPort;
import com.gvchat.im.message.domain.message.port.GroupMembershipPort;
import com.gvchat.im.message.domain.message.port.ChannelMembershipPort;
import com.gvchat.im.message.domain.message.port.MessagePayloadCodecPort;
import com.gvchat.im.message.domain.message.port.MentionResolverPort;
import com.gvchat.im.message.domain.message.port.FeatureTogglePort;
import com.gvchat.im.message.domain.message.port.UnreadCountProjectionPort;
import com.gvchat.im.message.domain.message.repository.MessageFavoriteRepository;
import com.gvchat.im.message.domain.message.repository.MessageOutboxRepository;
import com.gvchat.im.message.domain.message.repository.MessageReadStatusRepository;
import com.gvchat.im.message.domain.message.repository.MessageRepository;
import com.gvchat.im.message.domain.message.port.AdminMessageTotalCountPort;
import com.gvchat.im.message.media.MediaReferenceClient;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageApplicationServiceTest {
  private final MessageFavoriteRepository favoriteRepository = mock(MessageFavoriteRepository.class);

  @Test
  void shouldStoreAuthoritativeMessageAndCompatibleOutboxEventOnce() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(), outboxes);
    StoreMessageCommand command = command("command-1");

    Message stored = service.store(command);
    Message duplicate = service.store(command);

    assertSame(stored, duplicate);
    assertEquals(1, messages.items.size());
    assertEquals(1, outboxes.items.size());
    assertEquals("command-1", outboxes.items.getFirst().getEventId());
    assertEquals("conversation-1", outboxes.items.getFirst().getShardingKey());
    assertEquals(1L, stored.getSeq());
  }

  @Test
  void shouldTreatSameSenderAndClientMessageIdAsDuplicateAcrossCommandRetries() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(), outboxes);

    Message stored = service.store(command("command-1"));
    Message duplicate = service.store(command("command-2"));

    assertSame(stored, duplicate);
    assertEquals(1, messages.items.size());
    assertEquals(1, outboxes.items.size());
  }

  @Test
  void shouldResolveGroupRecipientsBeforeWritingOutboxEvent() {
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    CapturingMessagePayloadCodec codec = new CapturingMessagePayloadCodec();
    GroupMembershipPort groupMembershipPort = new GroupMembershipPort() {
      @Override public boolean isMember(long groupId, long userId) { return true; }
      @Override public boolean isOwnerOrAdmin(long groupId, long userId) { return true; }
      @Override public List<Long> findMemberUserIds(long groupId) { return List.of(7L, 9L, 11L); }
    };
    MessageApplicationService service = service(new InMemoryMessageRepository(), new InMemoryReadStatusRepository(),
        outboxes, codec, (userId, peerUserId) -> true, groupMembershipPort);

    service.store(new StoreMessageCommand("command-1", "group:100", 7L, "sender", "client-1", "group", "100",
        "text", "hello", null, "[]", Instant.parse("2026-07-22T00:00:00Z")));

    assertEquals(List.of(9L, 11L), codec.recipientUserIds);
  }

  @Test
  void shouldResolvePrivateRecipientBeforeWritingOutboxEvent() {
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    CapturingMessagePayloadCodec codec = new CapturingMessagePayloadCodec();
    MessageApplicationService service = service(new InMemoryMessageRepository(), new InMemoryReadStatusRepository(),
        outboxes, codec, (userId, peerUserId) -> true, groupMembershipPort(true));

    service.store(command("command-1"));

    assertEquals(List.of(9L), codec.recipientUserIds);
  }

  @Test
  void shouldResolveChannelSubscribersAsRecipientsAndWriteSyncIndexes() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryReadStatusRepository readStatuses = new InMemoryReadStatusRepository();
    InMemoryUserSyncIndexRepository syncIndexes = new InMemoryUserSyncIndexRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    CapturingMessagePayloadCodec codec = new CapturingMessagePayloadCodec();
    ChannelMembershipPort channelPort = new ChannelMembershipPort() {
      @Override public boolean isOwner(long channelId, long userId) { return userId == 7L; }
      @Override public boolean isSubscribed(long channelId, long userId) { return false; }
      @Override public List<Long> listSubscriberUserIds(long channelId) { return List.of(9L, 11L); }
    };
    MessageApplicationService service = service(messages, readStatuses, syncIndexes, outboxes, codec,
        (userId, peerUserId) -> true, groupMembershipPort(true), new InMemoryUnreadCountProjection(), channelPort);

    service.store(new StoreMessageCommand("command-1", "conv:channel:100", 7L, "owner", "client-1", "channel", "100",
        "text", "hello", null, "[]", Instant.parse("2026-07-22T00:00:00Z")));

    assertEquals(List.of(9L, 11L), codec.recipientUserIds);
    // 订阅者（9、11）+ 发布者（7）均写 per-user 同步索引，驱动 WS 推送 / 离线通知 / 未读。
    assertEquals(3, syncIndexes.indexes.size());
  }

  @Test
  void shouldRejectSendingToGroupWhenSenderIsNotMember() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(), outboxes,
        new CapturingMessagePayloadCodec(), (userId, peerUserId) -> true, groupMembershipPort(false));

    assertThrows(ApiException.class, () -> service.store(new StoreMessageCommand("command-1", "group:100", 7L,
        "sender", "client-1", "group", "100", "text", "hello", null, "[]", Instant.now())));

    assertEquals(0, messages.items.size());
    assertEquals(0, outboxes.items.size());
  }

  @Test
  void shouldStoreGroupSystemMessageVisibleToAllMembers() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryUserSyncIndexRepository syncIndexes = new InMemoryUserSyncIndexRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    CapturingMessagePayloadCodec codec = new CapturingMessagePayloadCodec();
    // 系统发送者(user 0)不是群成员，且不经过成员/禁言校验。
    GroupMembershipPort groupMembershipPort = new GroupMembershipPort() {
      @Override public boolean isMember(long groupId, long userId) { return false; }
      @Override public boolean isOwnerOrAdmin(long groupId, long userId) { return false; }
      @Override public List<Long> findMemberUserIds(long groupId) { return List.of(7L, 9L, 11L); }
    };
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(), syncIndexes, outboxes,
        codec, (userId, peerUserId) -> true, groupMembershipPort, new InMemoryUnreadCountProjection());

    Message stored = service.store(new StoreMessageCommand("command-sys", "group:100", 0L, "", null, "group", "100",
        "system", "7 邀请 9 加入了群聊", null, "[]", Instant.parse("2026-07-22T00:00:00Z")));

    assertNotNull(stored);
    // 全体群成员均为接收方（系统发送者 0 不是成员，不参与排除）。
    assertEquals(List.of(7L, 9L, 11L), codec.recipientUserIds);
    // 每个成员各写一条 per-user 同步索引，且不产生 user 0 的同步索引。
    assertEquals(3, syncIndexes.indexes.size());
    assertTrue(syncIndexes.indexes.stream().noneMatch(idx -> idx.userId() == 0L));
  }

  @Test
  void shouldRejectSystemMessageFromRegularUser() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(), outboxes,
        new CapturingMessagePayloadCodec(), (userId, peerUserId) -> true, groupMembershipPort(true));

    // 普通群成员(7)试图以 msgType=system 伪造系统消息，必须被拒绝。
    ApiException ex = assertThrows(ApiException.class, () -> service.store(new StoreMessageCommand("command-forge",
        "group:100", 7L, "sender", "client-1", "group", "100", "system", "fake system notice", null, "[]",
        Instant.parse("2026-07-22T00:00:00Z"))));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
    assertEquals(0, messages.items.size());
    assertEquals(0, outboxes.items.size());
  }

  @Test
  void shouldRejectGroupHistoryForNonMember() {
    MessageApplicationService service = service(new InMemoryMessageRepository(), new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository(), (userId, peerUserId) -> true, groupMembershipPort(false));

    assertThrows(ApiException.class, () -> service.getHistory(
        new com.gvchat.im.message.application.query.MessageHistoryQuery(7L, "100", ChatType.GROUP, 1, 20)));
  }

  @Test
  void centeredHistoryReturnsWindowAroundCenterMessage() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository(), (userId, peerUserId) -> true, groupMembershipPort(true));
    // 同会话 seq 1..5。
    for (long i = 1; i <= 5; i++) {
      messages.save(Message.create("m-" + i, "conv:private:7:9", i, 7L, "sender", "9", ChatType.PRIVATE,
          MsgType.TEXT, "hello " + i, "c-" + i, null, List.of(), java.time.LocalDateTime.now()));
    }

    // 以 m-3 为中心：before=1、after=1 → [m-2, m-3, m-4]。
    var result = service.getHistory(new com.gvchat.im.message.application.query.MessageHistoryQuery(
        7L, "9", ChatType.PRIVATE, null, null, "m-3", 1, 1, null));

    assertEquals(List.of("m-2", "m-3", "m-4"),
        result.stream().map(com.gvchat.im.message.application.result.MessageResult::msgId).toList());
  }

  @Test
  void centeredHistoryReturnsEmptyWhenCenterMissing() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository(), (userId, peerUserId) -> true, groupMembershipPort(true));
    messages.save(Message.create("m-1", "conv:private:7:9", 1, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", "c-1", null, List.of(), java.time.LocalDateTime.now()));

    var result = service.getHistory(new com.gvchat.im.message.application.query.MessageHistoryQuery(
        7L, "9", ChatType.PRIVATE, null, null, "not-exist", 5, 5, null));

    assertTrue(result.isEmpty(), "中心消息不存在时应返回空，客户端据此静默降级");
  }

  @Test
  void shouldOnlyCountNewReadStatuses() {
    InMemoryReadStatusRepository readStatuses = new InMemoryReadStatusRepository();
    InMemoryUnreadCountProjection projection = new InMemoryUnreadCountProjection();
    MessageApplicationService service = service(new InMemoryMessageRepository(), readStatuses,
        new InMemoryMessageOutboxRepository(), projection);

    assertEquals(2, service.markRead(new MarkMessagesReadCommand(9L, List.of("m-1", "m-2"))).count());
    assertEquals(1, service.markRead(new MarkMessagesReadCommand(9L, List.of("m-2", "m-3"))).count());
    assertEquals(3, readStatuses.items.size());
    assertEquals(2, projection.invalidations);
  }

  @Test
  void shouldSynchronizeStoredMessagesFromRecipientSequenceAndAuthorizeReadStatus() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryReadStatusRepository readStatuses = new InMemoryReadStatusRepository();
    InMemoryUserSyncIndexRepository syncIndexes = new InMemoryUserSyncIndexRepository();
    MessageApplicationService service = service(messages, readStatuses, syncIndexes,
        new InMemoryMessageOutboxRepository());

    service.store(command("command-1"));

    var result = service.sync(new MessageSyncQuery(9L, 0L, 20));
    assertEquals(1, result.items().size());
    assertEquals("command-1", result.items().getFirst().message().msgId());
    assertNull(result.items().getFirst().readAt());
    assertEquals(1L, result.nextSyncSeq());
    assertEquals(1, service.markRead(new MarkMessagesReadCommand(9L, List.of("command-1"))).count());
    var reread = service.sync(new MessageSyncQuery(9L, 0L, 20));
    assertEquals("command-1", reread.items().getFirst().message().msgId());
    assertNotNull(reread.items().getFirst().readAt());
    assertEquals(0, service.markRead(new MarkMessagesReadCommand(10L, List.of("command-1"))).count());
  }

  @Test
  void shouldAlwaysQueryFeatureFilteredUnreadCountFromMysql() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    messages.unreadCount = 4;
    InMemoryUnreadCountProjection projection = new InMemoryUnreadCountProjection();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository(), projection);

    assertEquals(4, service.getUnreadCount(9L));
    assertEquals(4, projection.value);
    assertEquals(1, messages.unreadCountQueries);
    assertEquals(4, service.getUnreadCount(9L));
    assertEquals(2, messages.unreadCountQueries);
  }

  @Test
  void shouldPassCurrentFeatureSwitchesToUnreadQueries() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    messages.unreadCount = 3;
    messages.conversationUnreads = List.of(new MessageRepository.ConversationUnread("group:100", 3));
    MessageApplicationService service = service(messages, featureTogglePort(false, true, false));

    assertEquals(3, service.getUnreadCount(9L));
    assertEquals(List.of(new ConversationUnreadResult("group:100", 3)), service.getUnreadByConversation(9L));
    assertEquals(Boolean.FALSE, messages.lastPrivateEnabled);
    assertEquals(Boolean.TRUE, messages.lastGroupEnabled);
    assertEquals(Boolean.FALSE, messages.lastChannelEnabled);
  }

  @Test
  void shouldUseFriendPortBeforeClearingPrivateChat() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository(), (userId, peerUserId) -> false, groupMembershipPort(false));

    assertThrows(ApiException.class, () -> service.clearPrivateChat(new ClearPrivateChatCommand(7L, "9")));
    assertEquals(0, messages.privateChatDeletes);
  }

  @Test
  void shouldHardDeleteMessageTracesOnDelete() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryReadStatusRepository readStatuses = new InMemoryReadStatusRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    TestMessagePayloadCodec codec = new TestMessagePayloadCodec();
    MessageApplicationService service = service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes,
        codec, (userId, peerUserId) -> true, groupMembershipPort(true), new InMemoryUnreadCountProjection());
    service.store(command("m-1"));

    assertEquals("m-1", service.deleteForEveryone(new DeleteMessageCommand(7L, "m-1")).msgId());
    assertNull(messages.items.get("m-1"), "删除应硬删除服务端消息记录");
    assertEquals(List.of("m-1"), readStatuses.deletedMsgIds, "删除应硬删除已读状态痕迹");
    verify(favoriteRepository).deleteByMsgId("m-1");
    assertEquals(ImMqTopics.MESSAGE_RECALLED_EVENT, outboxes.items.getLast().getTopic());
    assertEquals(List.of(7L, 9L), codec.recalledEvent.recipientUserIds());
    assertEquals("deleted", codec.recalledEvent.reason());
  }

  @Test
  void shouldHardDeleteMessageTracesOnRecall() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryReadStatusRepository readStatuses = new InMemoryReadStatusRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    TestMessagePayloadCodec codec = new TestMessagePayloadCodec();
    MessageApplicationService service = service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes,
        codec, (userId, peerUserId) -> true, groupMembershipPort(true), new InMemoryUnreadCountProjection());
    // 撤回不限时：用「历史消息」（固定旧时间戳）也应能撤回。
    service.store(command("m-1"));

    assertEquals("m-1", service.deleteForEveryone(new DeleteMessageCommand(7L, "m-1", true)).msgId());
    assertNull(messages.items.get("m-1"), "撤回应硬删除服务端消息记录");
    assertEquals("recalled", codec.recalledEvent.reason());
  }

  @Test
  void shouldAllowPrivatePeerToDeleteOthersMessage() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), java.time.LocalDateTime.now()));

    // Telegram 语义：单聊对端（user=9）可删除发送方（user=7）的消息。
    assertEquals("m-1", service.deleteForEveryone(new DeleteMessageCommand(9L, "m-1")).msgId());
    assertNull(messages.items.get("m-1"), "对端删除应硬删除消息");
  }

  @Test
  void shouldRejectPeerRecall() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), java.time.LocalDateTime.now()));

    // 撤回（墓碑）仅发送方，对端撤回应被拒绝。
    ApiException ex = assertThrows(ApiException.class,
        () -> service.deleteForEveryone(new DeleteMessageCommand(9L, "m-1", true)));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
    assertNotNull(messages.items.get("m-1"), "对端撤回应被拒绝，消息应保留");
  }

  @Test
  void shouldAllowGroupAdminToDeleteOthersMessage() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryUserSyncIndexRepository(), new InMemoryMessageOutboxRepository(), new TestMessagePayloadCodec(),
        (userId, peerUserId) -> true, groupMembershipPort(true), new InMemoryUnreadCountProjection());
    messages.save(Message.create("m-1", "group:100", 1L, 7L, "sender", "100", ChatType.GROUP,
        MsgType.TEXT, "hello", null, null, List.of(), java.time.LocalDateTime.now()));

    // 群主/管理员（isOwnerOrAdmin=true）可删除任意成员消息。
    assertEquals("m-1", service.deleteForEveryone(new DeleteMessageCommand(11L, "m-1")).msgId());
    assertNull(messages.items.get("m-1"), "群主/管理员删除他人消息应硬删除消息");
  }

  @Test
  void shouldRejectGroupMemberDeletingOthersMessage() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryUserSyncIndexRepository(), new InMemoryMessageOutboxRepository(), new TestMessagePayloadCodec(),
        (userId, peerUserId) -> true, groupMembershipPort(false), new InMemoryUnreadCountProjection());
    messages.save(Message.create("m-1", "group:100", 1L, 7L, "sender", "100", ChatType.GROUP,
        MsgType.TEXT, "hello", null, null, List.of(), java.time.LocalDateTime.now()));

    // 普通成员（isOwnerOrAdmin=false）无权删除他人消息。
    ApiException ex = assertThrows(ApiException.class,
        () -> service.deleteForEveryone(new DeleteMessageCommand(11L, "m-1")));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
    assertNotNull(messages.items.get("m-1"), "普通成员无权删除他人消息，消息应保留");
  }

  @Test
  void shouldRetainSearchPaginationResponseFields() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), java.time.LocalDateTime.now()));

    var result = service.search(new SearchMessagesQuery(7L, "hello", "9", ChatType.PRIVATE, null, 1, 20));

    assertEquals(1, result.items().size());
    assertEquals(1, result.total());
    assertEquals(1, result.page());
    assertEquals(20, result.pageSize());
  }

  @Test
  void shouldEditMessageWithinWindowAndMarkEdited() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    TestMessagePayloadCodec codec = new TestMessagePayloadCodec();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryUserSyncIndexRepository(), outboxes, codec, (userId, peerUserId) -> true,
        groupMembershipPort(true), new InMemoryUnreadCountProjection());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), LocalDateTime.now(Clock.systemUTC())));

    var result = service.editMessage(new EditMessageCommand(7L, "m-1", "hello edited"));

    assertEquals("m-1", result.msgId());
    assertTrue(result.edited());
    Message stored = messages.items.get("m-1");
    assertEquals("hello edited", stored.getContent());
    assertTrue(stored.isEdited());
    assertNotNull(stored.getEditedAt());
    assertEquals(MessageEditedEvent.TOPIC, outboxes.items.getLast().getTopic());
  }

  @Test
  void shouldRejectEditBeyondWindow() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), LocalDateTime.now(Clock.systemUTC()).minusMinutes(5)));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.editMessage(new EditMessageCommand(7L, "m-1", "too late")));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void shouldRejectEditByNonSender() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());
    messages.save(Message.create("m-1", "private:7:9", 1L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", null, null, List.of(), LocalDateTime.now()));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.editMessage(new EditMessageCommand(9L, "m-1", "hack")));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void shouldResolveGroupMentionAtAllMarker() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MentionResolverPort resolver = (groupId, targets) -> new MentionResolverPort.MentionResolution(true, List.of());
    MessageApplicationService service = service(resolver, atUsersCodec(List.of("@all")), messages,
        new InMemoryReadStatusRepository(), new InMemoryUserSyncIndexRepository(), outboxes);

    Message stored = service.store(new StoreMessageCommand("command-all", "group:100", 7L, "sender", "client-1",
        "group", "100", "text", "hello everyone", null, "[\"@all\"]", Instant.parse("2026-07-22T00:00:00Z")));

    assertNotNull(stored);
    assertTrue(stored.getAtUsers().contains(Mention.AT_ALL));
  }

  @Test
  void shouldResolveGroupMentionNicknameToUserId() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    InMemoryMessageOutboxRepository outboxes = new InMemoryMessageOutboxRepository();
    MentionResolverPort resolver = (groupId, targets) -> new MentionResolverPort.MentionResolution(false, List.of(9L));
    MessageApplicationService service = service(resolver, atUsersCodec(List.of("张三")), messages,
        new InMemoryReadStatusRepository(), new InMemoryUserSyncIndexRepository(), outboxes);

    Message stored = service.store(new StoreMessageCommand("command-mention", "group:100", 7L, "sender", "client-1",
        "group", "100", "text", "hi @张三", null, "[\"张三\"]", Instant.parse("2026-07-22T00:00:00Z")));

    assertNotNull(stored);
    assertEquals(List.of("9"), stored.getAtUsers());
  }

  @Test
  void shouldReturnPerConversationUnread() {
    InMemoryMessageRepository messages = new InMemoryMessageRepository();
    messages.conversationUnreads = List.of(
        new MessageRepository.ConversationUnread("private:7:9", 3L),
        new MessageRepository.ConversationUnread("group:100", 2L));
    MessageApplicationService service = service(messages, new InMemoryReadStatusRepository(),
        new InMemoryMessageOutboxRepository());

    var result = service.getUnreadByConversation(9L);

    assertEquals(2, result.size());
    assertEquals("private:7:9", result.get(0).conversationId());
    assertEquals(3L, result.get(0).count());
  }

  private MessageApplicationService service(MentionResolverPort mentionResolverPort, MessagePayloadCodecPort codec,
      MessageRepository messages, MessageReadStatusRepository readStatuses,
      com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository syncIndexes,
      MessageOutboxRepository outboxes) {
    ConversationSequencePort sequencePort = new ConversationSequencePort() {
      private long sequence;
      @Override public long next(String conversationId) { return ++sequence; }
    };
    return new MessageApplicationService(messages, readStatuses, syncIndexes, outboxes, sequencePort, codec,
        (userId, peerUserId) -> true, groupMembershipPort(true), mentionResolverPort, channelMembershipPort(),
        new InMemoryUnreadCountProjection(), new InMemorySecretUnreadProjection(), mock(MediaReferenceClient.class), () -> List.of(), userId -> false,
        featureTogglePort(), mock(AdminMessageTotalCountPort.class), favoriteRepository, new InMemoryUserDeletedMessageRepository(), new InMemoryUserConversationClearRepository(),
        new com.gvchat.im.message.domain.message.port.HotMessageProjectionPort() {
          @Override public void deleteByMsgId(String msgId) { }
          @Override public void deleteByConversationId(String conversationId) { }
        });
  }

  private MessagePayloadCodecPort atUsersCodec(List<String> atUsers) {
    return new MessagePayloadCodecPort() {
      @Override public List<String> readAtUsers(String json) { return atUsers; }
      @Override public String writeStoredMessageEvent(Message message, List<Long> recipientUserIds,
          Map<Long, Long> recipientSyncSeqs, List<com.gvchat.protocol.mq.event.MessageMedia> media) { return "{}"; }
      @Override public String writeMessageRecalledEvent(com.gvchat.protocol.mq.event.MessageRecalledEvent event) { return "{}"; }
      @Override public String writeMessageEditedEvent(MessageEditedEvent event) { return "{}"; }
      @Override public String writeChatClearedEvent(com.gvchat.protocol.mq.event.ChatClearedEvent event) { return "{}"; }
    };
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      MessageOutboxRepository outboxes) {
    return service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes, new TestMessagePayloadCodec(), (userId, peerUserId) -> true,
        groupMembershipPort(true), new InMemoryUnreadCountProjection());
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      MessageOutboxRepository outboxes, UnreadCountProjectionPort unreadCountProjectionPort) {
    return service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes, new TestMessagePayloadCodec(), (userId, peerUserId) -> true,
        groupMembershipPort(true), unreadCountProjectionPort);
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      MessageOutboxRepository outboxes, FriendRelationPort friendRelationPort, GroupMembershipPort groupMembershipPort) {
    return service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes, new TestMessagePayloadCodec(), friendRelationPort,
        groupMembershipPort, new InMemoryUnreadCountProjection());
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      MessageOutboxRepository outboxes, MessagePayloadCodecPort payloadCodecPort, FriendRelationPort friendRelationPort,
      GroupMembershipPort groupMembershipPort) {
    return service(messages, readStatuses, new InMemoryUserSyncIndexRepository(), outboxes, payloadCodecPort, friendRelationPort, groupMembershipPort,
        new InMemoryUnreadCountProjection());
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository syncIndexes,
      MessageOutboxRepository outboxes) {
    return service(messages, readStatuses, syncIndexes, outboxes, new TestMessagePayloadCodec(), (userId, peerUserId) -> true,
        groupMembershipPort(true), new InMemoryUnreadCountProjection());
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository syncIndexes,
      MessageOutboxRepository outboxes, MessagePayloadCodecPort payloadCodecPort, FriendRelationPort friendRelationPort,
      GroupMembershipPort groupMembershipPort, UnreadCountProjectionPort unreadCountProjectionPort) {
    return service(messages, readStatuses, syncIndexes, outboxes, payloadCodecPort, friendRelationPort,
        groupMembershipPort, unreadCountProjectionPort, channelMembershipPort());
  }

  private MessageApplicationService service(MessageRepository messages, MessageReadStatusRepository readStatuses,
      com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository syncIndexes,
      MessageOutboxRepository outboxes, MessagePayloadCodecPort payloadCodecPort, FriendRelationPort friendRelationPort,
      GroupMembershipPort groupMembershipPort, UnreadCountProjectionPort unreadCountProjectionPort,
      ChannelMembershipPort channelMembershipPort) {
    ConversationSequencePort sequencePort = new ConversationSequencePort() {
      private long sequence;
      @Override public long next(String conversationId) { return ++sequence; }
    };
    return new MessageApplicationService(messages, readStatuses, syncIndexes, outboxes, sequencePort, payloadCodecPort,
        friendRelationPort, groupMembershipPort, mentionResolverPort(), channelMembershipPort, unreadCountProjectionPort, new InMemorySecretUnreadProjection(),
        mock(MediaReferenceClient.class), () -> List.of(), userId -> false, featureTogglePort(),
        mock(AdminMessageTotalCountPort.class), favoriteRepository, new InMemoryUserDeletedMessageRepository(), new InMemoryUserConversationClearRepository(),
        new com.gvchat.im.message.domain.message.port.HotMessageProjectionPort() {
          @Override public void deleteByMsgId(String msgId) { }
          @Override public void deleteByConversationId(String conversationId) { }
        });
  }

  /** 会话清空标记的内存实现：验证同步返回 clearedConversations。 */
  private static final class InMemoryUserConversationClearRepository
      implements com.gvchat.im.message.domain.message.repository.UserConversationClearRepository {
    private final java.util.Map<String, ClearedConversation> cleared = new java.util.HashMap<>();

    @Override public void markCleared(long userId, String conversationId, String chatType,
        java.time.LocalDateTime clearedAt, long clearedBy) {
      cleared.put(userId + ":" + conversationId, new ClearedConversation(conversationId, chatType, clearedAt));
    }

    @Override public java.util.List<ClearedConversation> findByUserId(long userId) {
      return cleared.entrySet().stream().filter(e -> e.getKey().startsWith(userId + ":"))
          .map(java.util.Map.Entry::getValue).toList();
    }
  }

  /** 「删除仅我」墓碑的内存实现：测试内验证同步/历史过滤行为。 */
  private static final class InMemoryUserDeletedMessageRepository
      implements com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository {
    private final java.util.Set<String> deleted = new java.util.HashSet<>();

    @Override public void markDeleted(long userId, String msgId, String conversationId, String chatType) {
      deleted.add(userId + ":" + msgId);
    }

    @Override public int markDeleted(long userId, List<String> msgIds) {
      int added = 0;
      for (String msgId : msgIds) {
        if (deleted.add(userId + ":" + msgId)) {
          added++;
        }
      }
      return added;
    }

    @Override public List<String> findDeletedMsgIds(long userId, List<String> msgIds) {
      return msgIds.stream().filter(id -> deleted.contains(userId + ":" + id)).toList();
    }

    @Override public List<DeletedMessage> findAllByUserId(long userId) {
      String prefix = userId + ":";
      return deleted.stream().filter(key -> key.startsWith(prefix))
          .map(key -> new DeletedMessage(key.substring(prefix.length()), "", "private")).toList();
    }

    @Override public void deleteByConversation(long userId, String conversationId) {
      deleted.removeIf(key -> key.startsWith(userId + ":"));
    }
  }

  private MentionResolverPort mentionResolverPort() {
    return (groupId, targets) -> MentionResolverPort.MentionResolution.empty();
  }

  private static FeatureTogglePort featureTogglePort() {
    return featureTogglePort(true, true, true);
  }

  private MessageApplicationService service(MessageRepository messages, FeatureTogglePort featureTogglePort) {
    ConversationSequencePort sequencePort = conversationId -> 1L;
    return new MessageApplicationService(messages, new InMemoryReadStatusRepository(),
        new InMemoryUserSyncIndexRepository(), new InMemoryMessageOutboxRepository(), sequencePort,
        new TestMessagePayloadCodec(), (userId, peerUserId) -> true, groupMembershipPort(true),
        mentionResolverPort(), channelMembershipPort(), new InMemoryUnreadCountProjection(), new InMemorySecretUnreadProjection(),
        mock(MediaReferenceClient.class), () -> List.of(), userId -> false, featureTogglePort,
        mock(AdminMessageTotalCountPort.class), favoriteRepository, new InMemoryUserDeletedMessageRepository(), new InMemoryUserConversationClearRepository(),
        new com.gvchat.im.message.domain.message.port.HotMessageProjectionPort() {
          @Override public void deleteByMsgId(String msgId) { }
          @Override public void deleteByConversationId(String conversationId) { }
        });
  }

  private static FeatureTogglePort featureTogglePort(boolean privateEnabled, boolean groupEnabled,
      boolean channelEnabled) {
    return new FeatureTogglePort() {
      @Override public boolean isPrivateChatEnabled() { return privateEnabled; }
      @Override public boolean isGroupChatEnabled() { return groupEnabled; }
      @Override public boolean isChannelEnabled() { return channelEnabled; }
      @Override public boolean isChatDeleteEnabled() { return true; }
    };
  }

  private GroupMembershipPort groupMembershipPort(boolean member) {
    return new GroupMembershipPort() {
      @Override public boolean isMember(long groupId, long userId) { return member; }
      @Override public boolean isOwnerOrAdmin(long groupId, long userId) { return member; }
      @Override public List<Long> findMemberUserIds(long groupId) { return List.of(); }
    };
  }

  private ChannelMembershipPort channelMembershipPort() {
    return new ChannelMembershipPort() {
      @Override public boolean isOwner(long channelId, long userId) { return false; }
      @Override public boolean isSubscribed(long channelId, long userId) { return false; }
      @Override public List<Long> listSubscriberUserIds(long channelId) { return List.of(); }
    };
  }

  private StoreMessageCommand command(String commandId) {
    return new StoreMessageCommand(commandId, "conversation-1", 7L, "sender", "client-1", "private", "9",
        "text", "hello", null, "[\"9\"]", Instant.parse("2026-07-22T00:00:00Z"));
  }

  private static final class InMemoryMessageRepository implements MessageRepository {
    private final Map<String, Message> items = new HashMap<>();
    private int privateChatDeletes;
    private long unreadCount;
    private int unreadCountQueries;
    private Boolean lastPrivateEnabled;
    private Boolean lastGroupEnabled;
    private Boolean lastChannelEnabled;
    private List<MessageRepository.ConversationUnread> conversationUnreads = List.of();
    @Override public Optional<Message> findByMsgId(String msgId) { return Optional.ofNullable(items.get(msgId)); }
    @Override public Optional<Message> findBySenderIdAndClientMsgId(long senderId, String clientMsgId) {
      return items.values().stream()
          .filter(item -> item.getFromUserId() == senderId && clientMsgId.equals(item.getClientMsgId()))
          .findFirst();
    }
    @Override public List<Message> findByMsgIds(List<String> msgIds) {
      return msgIds.stream().map(items::get).filter(java.util.Objects::nonNull).toList();
    }
    @Override public Message save(Message message) { items.put(message.getMsgId(), message); return message; }
    @Override public Message update(Message message) { items.put(message.getMsgId(), message); return message; }
    @Override public List<Message> findHistory(long userId, String peerId, ChatType chatType, int page, int pageSize) { return List.copyOf(items.values()); }
    @Override public List<String> findHistoryDates(long userId, String peerId, ChatType chatType) {
      return items.values().stream()
          .map(Message::getCreatedAt)
          .filter(java.util.Objects::nonNull)
          .map(t -> t.toLocalDate().toString())
          .distinct()
          .sorted(java.util.Comparator.reverseOrder())
          .toList();
    }
    @Override public List<Message> findHistoryByDate(long userId, String peerId, ChatType chatType,
        java.time.LocalDate date, int page, int pageSize) {
      return items.values().stream()
          .filter(m -> m.getCreatedAt() != null && m.getCreatedAt().toLocalDate().equals(date))
          .sorted(java.util.Comparator.comparing(Message::getCreatedAt).reversed())
          .toList();
    }
    @Override public List<Message> findCentered(long userId, String peerId, ChatType chatType, String centerMsgId,
        int beforeCount, int afterCount) {
      Message center = items.get(centerMsgId);
      if (center == null) {
        return List.of();
      }
      return items.values().stream()
          .filter(m -> center.getConversationId().equals(m.getConversationId()))
          .filter(m -> m.getSeq() >= center.getSeq() - beforeCount && m.getSeq() <= center.getSeq() + afterCount)
          .sorted(java.util.Comparator.comparing(Message::getSeq))
          .toList();
    }
    @Override public List<Message> findChannelMessages(String conversationId, long afterSeq, int limit) {
      return items.values().stream()
          .filter(m -> conversationId.equals(m.getConversationId()) && m.getSeq() > afterSeq)
          .sorted(java.util.Comparator.comparing(Message::getSeq))
          .limit(limit)
          .toList();
    }
    @Override public MessagePage search(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType, int page, int pageSize) {
      List<Message> found = items.values().stream().filter(item -> item.getContent().contains(keyword)).toList();
      return new MessagePage(found, found.size());
    }
    @Override public List<Message> findAdminPage(int page, int pageSize) { return List.copyOf(items.values()); }
    @Override public long count() { return items.size(); }
    @Override public long countCreatedSince(LocalDateTime createdAt) { return items.size(); }
    @Override public List<Map<String, Object>> dailyCounts(LocalDateTime since) { return List.of(); }
    @Override public long countDistinctSendersSince(LocalDateTime since) { return 0L; }
    @Override public long countUnreadForUser(long userId, boolean privateEnabled, boolean groupEnabled,
        boolean channelEnabled) {
      unreadCountQueries++;
      lastPrivateEnabled = privateEnabled;
      lastGroupEnabled = groupEnabled;
      lastChannelEnabled = channelEnabled;
      return unreadCount;
    }
    @Override public List<MessageRepository.ConversationUnread> countUnreadByConversationForUser(long userId,
        boolean privateEnabled, boolean groupEnabled, boolean channelEnabled) {
      lastPrivateEnabled = privateEnabled;
      lastGroupEnabled = groupEnabled;
      lastChannelEnabled = channelEnabled;
      return conversationUnreads;
    }
    @Override public void deletePrivateChat(long userId, long peerUserId) { privateChatDeletes++; }
    @Override public void deleteGroupChat(long groupId) { }
    @Override public void deleteByMsgId(String msgId) { items.remove(msgId); }
  }

  private static final class InMemoryUnreadCountProjection implements UnreadCountProjectionPort {
    private long value = -1;
    private int invalidations;
    @Override public java.util.OptionalLong find(long userId) {
      return value < 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(value);
    }
    @Override public void replace(long userId, long count) { value = count; }
    @Override public void invalidate(long userId) { value = -1; invalidations++; }
  }

  private static final class InMemorySecretUnreadProjection
      implements com.gvchat.im.message.domain.message.port.SecretUnreadProjectionPort {
    @Override public void record(String chatType, long conversationId, long userId, String msgId, long seq,
        LocalDateTime occurredAt) { }
    @Override public void markRead(String chatType, long conversationId, long userId, long afterSeq) { }
    @Override public void deleteByMessage(String chatType, long conversationId, String msgId) { }
    @Override public long countForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) {
      return 0;
    }
    @Override public List<com.gvchat.im.message.domain.message.port.SecretUnreadProjectionPort.ConversationUnread>
        countByConversationForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) {
      return List.of();
    }
  }

  private static final class TestMessagePayloadCodec implements MessagePayloadCodecPort {
    private com.gvchat.protocol.mq.event.MessageRecalledEvent recalledEvent;
    @Override public List<String> readAtUsers(String atUsersJson) { return List.of("9"); }
    @Override public String writeStoredMessageEvent(Message message, List<Long> recipientUserIds,
        Map<Long, Long> recipientSyncSeqs,
        List<com.gvchat.protocol.mq.event.MessageMedia> media) {
      return "{\"msgId\":\"" + message.getMsgId() + "\"}";
    }
    @Override public String writeMessageRecalledEvent(com.gvchat.protocol.mq.event.MessageRecalledEvent event) {
      recalledEvent = event;
      return "{\"msgId\":\"" + event.msgId() + "\"}";
    }
    @Override public String writeMessageEditedEvent(com.gvchat.im.message.domain.message.event.MessageEditedEvent event) {
      return "{\"msgId\":\"" + event.msgId() + "\"}";
    }
    @Override public String writeChatClearedEvent(com.gvchat.protocol.mq.event.ChatClearedEvent event) {
      return "{}";
    }
  }

  private static final class CapturingMessagePayloadCodec implements MessagePayloadCodecPort {
    private List<Long> recipientUserIds;
    @Override public List<String> readAtUsers(String atUsersJson) { return List.of(); }
    @Override public String writeStoredMessageEvent(Message message, List<Long> recipientUserIds,
        Map<Long, Long> recipientSyncSeqs,
        List<com.gvchat.protocol.mq.event.MessageMedia> media) {
      this.recipientUserIds = recipientUserIds;
      return "{}";
    }
    @Override public String writeMessageRecalledEvent(com.gvchat.protocol.mq.event.MessageRecalledEvent event) {
      return "{}";
    }
    @Override public String writeMessageEditedEvent(com.gvchat.im.message.domain.message.event.MessageEditedEvent event) {
      return "{}";
    }
    @Override public String writeChatClearedEvent(com.gvchat.protocol.mq.event.ChatClearedEvent event) {
      return "{}";
    }
  }

  private static final class InMemoryUserSyncIndexRepository implements com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository {
    private final Map<Long, Long> sequences = new HashMap<>();
    private final List<com.gvchat.im.message.domain.message.model.UserSyncIndex> indexes = new ArrayList<>();
    @Override public long nextSequence(long userId) { return sequences.merge(userId, 1L, Long::sum); }
    @Override public void saveAll(List<com.gvchat.im.message.domain.message.model.UserSyncIndex> values) { indexes.addAll(values); }
    @Override public List<com.gvchat.im.message.domain.message.model.UserSyncIndex> findAfter(long userId, long afterSyncSeq, int limit) {
      return indexes.stream().filter(item -> item.userId() == userId && item.syncSeq() > afterSyncSeq).limit(limit).toList();
    }
    @Override public List<Long> findRecipientUserIdsByMsgId(String msgId) {
      return indexes.stream().filter(item -> item.msgId().equals(msgId))
          .map(com.gvchat.im.message.domain.message.model.UserSyncIndex::userId).distinct().toList();
    }
    @Override public java.util.Set<String> findOwnedMessageIds(long userId, java.util.Collection<String> msgIds) {
      if (indexes.isEmpty()) return java.util.Set.copyOf(msgIds);
      return indexes.stream().filter(item -> item.userId() == userId && msgIds.contains(item.msgId()))
          .map(com.gvchat.im.message.domain.message.model.UserSyncIndex::msgId).collect(java.util.stream.Collectors.toSet());
    }
    @Override public void deleteByMsgId(String msgId) { indexes.removeIf(item -> item.msgId().equals(msgId)); }
  }

  private static final class InMemoryReadStatusRepository implements MessageReadStatusRepository {
    private final List<MessageReadStatus> items = new ArrayList<>();
    private final List<String> deletedMsgIds = new ArrayList<>();
    @Override public boolean saveIfAbsent(MessageReadStatus readStatus) {
      if (items.stream().anyMatch(item -> item.msgId().equals(readStatus.msgId())
          && item.userId() == readStatus.userId())) {
        return false;
      }
      items.add(readStatus);
      return true;
    }
    @Override public Map<String, java.time.LocalDateTime> findReadAtByUserIdAndMsgIds(long userId,
        java.util.Collection<String> msgIds) {
      return items.stream().filter(item -> item.userId() == userId && msgIds.contains(item.msgId()))
          .collect(java.util.stream.Collectors.toMap(MessageReadStatus::msgId, MessageReadStatus::readAt));
    }
    @Override public void deleteByMsgId(String msgId) { deletedMsgIds.add(msgId); }
  }

  private static final class InMemoryMessageOutboxRepository implements MessageOutboxRepository {
    private final List<MessageOutbox> items = new ArrayList<>();
    @Override public MessageOutbox save(MessageOutbox outbox) { items.add(outbox); return outbox; }
    @Override public List<MessageOutbox> findPending(int batchSize) { return List.of(); }
  }
}
