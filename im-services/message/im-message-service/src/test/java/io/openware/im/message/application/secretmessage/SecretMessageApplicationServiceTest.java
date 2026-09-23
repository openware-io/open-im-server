package io.openware.im.message.application.secretmessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.message.application.secretmessage.command.PostSecretMessageCommand;
import io.openware.im.message.application.secretmessage.result.SecretMessageResult;
import io.openware.im.message.domain.message.model.MessageOutbox;
import io.openware.im.message.domain.message.repository.MessageOutboxRepository;
import io.openware.im.message.domain.secretmessage.model.SecretMessage;
import io.openware.im.message.domain.secretmessage.model.SecretMessageDestroyed;
import io.openware.im.message.domain.message.port.FeatureTogglePort;
import io.openware.im.message.domain.secretmessage.port.SecretChatParticipantPort;
import io.openware.im.message.domain.secretmessage.port.SecretChatPolicyPort;
import io.openware.im.message.domain.secretmessage.repository.SecretMessageDestroyedRepository;
import io.openware.im.message.domain.secretmessage.repository.SecretMessageRepository;
import io.openware.protocol.mq.topic.ImMqTopics;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretMessageApplicationServiceTest {

  @Test
  void postStoresCiphertextWithActiveStatusAndNoDestroyAt() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    SecretMessageApplicationService service = service(repository, policy("off"));

    SecretMessageResult result = service.post(1L,
        new PostSecretMessageCommand(10L, "msg-1", "cipher-1", null));

    assertEquals("active", result.status());
    assertNull(result.destroyAt());
    assertEquals("cipher-1", result.ciphertext());
    assertEquals(1L, result.seq());
  }

  @Test
  void postIsRejectedWhenSecretChatIsDisabled() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    SecretMessageApplicationService service = new SecretMessageApplicationService(repository,
        new InMemorySecretMessageDestroyedRepository(), policy("off"), participants(),
        (chatId, msgIds, delaySeconds) -> true, new InMemoryOutboxRepository(), null,
        featureTogglePort(false), new InMemorySecretUnreadProjection(), new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), new io.openware.im.message.domain.message.repository.UserDeletedMessageRepository() {
      @Override public void markDeleted(long userId, String msgId, String conversationId, String chatType) { }
      @Override public int markDeleted(long userId, java.util.List<String> msgIds) { return 0; }
      @Override public java.util.List<String> findDeletedMsgIds(long userId, java.util.List<String> msgIds) { return java.util.List.of(); }
      @Override public java.util.List<DeletedMessage> findAllByUserId(long userId) { return java.util.List.of(); }
      @Override public void deleteByConversation(long userId, String conversationId) { }
    });

    assertThrows(ApiException.class,
        () -> service.post(1L, new PostSecretMessageCommand(10L, "msg-1", "cipher-1", null)));
    assertTrue(repository.items.isEmpty());
  }

  @Test
  void postQueuesSecretMessageStoredEventForOfflinePush() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    SecretMessageApplicationService service = service(repository,
        new InMemorySecretMessageDestroyedRepository(), policy("off"), outbox);

    service.post(1L, new PostSecretMessageCommand(10L, "msg-1", "cipher-1", null));

    assertEquals(1, outbox.items.size());
    assertEquals(ImMqTopics.SECRET_MESSAGE_STORED_EVENT, outbox.items.getFirst().getTopic());
    assertEquals("conv:secret:10", outbox.items.getFirst().getShardingKey());
    assertFalse(outbox.items.getFirst().getPayloadJson().contains("cipher-1"),
        "离线推送事件不得包含密文或明文内容");
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("\"recipientUserId\":2"),
        "事件应携带接收方用于离线推送");
  }

  @Test
  void listNeverStartsDestroyCountdownUntilExplicitRead() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.items.add(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    // 接收方拉取（未显式已读上报）不得计时——杜绝「未查阅就销毁」。
    List<SecretMessageResult> results = service.list(2L, 10L, 0L, 50);

    assertEquals(1, results.size());
    assertNull(results.getFirst().destroyAt(), "拉取不等于已读，不得开始计时");
  }

  @Test
  void listBySenderDoesNotStartDestroyCountdown() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.items.add(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    List<SecretMessageResult> results = service.list(1L, 10L, 0L, 50);

    assertEquals(1, results.size());
    assertNull(results.getFirst().destroyAt());
  }

  @Test
  void listWithOffPolicyNeverStartsDestroyCountdown() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.items.add(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("off"));

    List<SecretMessageResult> results = service.list(2L, 10L, 0L, 50);

    assertNull(results.getFirst().destroyAt());
  }

  @Test
  void listByNonParticipantIsForbidden() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.items.add(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    ApiException ex = assertThrows(ApiException.class, () -> service.list(3L, 10L, 0L, 50));
    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void postByNonParticipantIsForbidden() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    SecretMessageApplicationService service = service(repository, policy("off"));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.post(3L, new PostSecretMessageCommand(10L, "msg-1", "cipher-1", null)));
    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void markReadByNonParticipantIsForbidden() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    ApiException ex = assertThrows(ApiException.class, () -> service.markRead(3L, 10L, 1L));
    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void markReadByReceiverStartsDestroyCountdownForActiveUncountedMessages() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    // 接收方已读上报后才开始 30s 计时。
    var result = service.markRead(2L, 10L, 1L);

    assertEquals(1, result.counted());
    assertNotNull(repository.findByMsgId(10L, "msg-1").get().getDestroyAt());
    assertNotNull(result.destroyAt(), "已读上报应返回计时截止时刻供客户端本地销毁");
  }

  @Test
  void delayedDestroyCommandDestroysOnlyDueActiveMessagesAndPublishesEvent() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyedRepo = new InMemorySecretMessageDestroyedRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    // 一条到期 active（destroyAt 已到）+ 一条未到期 + 一条已 destroyed。
    repository.save(SecretMessage.restore(1L, 10L, "due-1", 1L, "cipher-1", 1L, "active",
        now.minusSeconds(1), 1L, now, 1L, now));
    repository.save(SecretMessage.restore(2L, 10L, "future-1", 1L, "cipher-2", 2L, "active",
        now.plusSeconds(30), 1L, now, 1L, now));
    repository.save(SecretMessage.restore(3L, 10L, "dead-1", 1L, "cipher-3", 3L, "destroyed",
        now.minusSeconds(5), 1L, now, 1L, now));
    SecretMessageApplicationService service = service(repository, destroyedRepo, policy("30s"), outbox);

    // 延迟命令到期：只销毁 due-1（active 且 destroyAt 已到），并发布销毁事件。
    int destroyed = service.destroyDelayed(10L, List.of("due-1", "future-1", "dead-1"), now);

    assertEquals(1, destroyed);
    assertTrue(repository.findByMsgId(10L, "due-1").isEmpty(), "到期密文应被硬删除");
    assertEquals("active", repository.findByMsgId(10L, "future-1").get().getStatus(),
        "未到销毁时刻的消息不得提前销毁");
    assertEquals(1, destroyedRepo.items.size());
    assertEquals("due-1", destroyedRepo.items.getFirst().msgId());
    assertEquals(1, outbox.items.size());
    assertEquals(ImMqTopics.SECRET_MESSAGE_DESTROYED_EVENT, outbox.items.getFirst().getTopic());
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("due-1"));
  }

  @Test
  void markReadDoesNotCountdownSendersOwnMessages() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    // 发送方自己上报已读：不触发对己方消息的计时。
    var result = service.markRead(1L, 10L, 1L);

    assertEquals(0, result.counted());
    assertNull(repository.findByMsgId(10L, "msg-1").get().getDestroyAt());
  }

  @Test
  void markReadWithOffPolicyNeverStartsCountdown() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L, LocalDateTime.now()));
    SecretMessageApplicationService service = service(repository, policy("off"));

    var result = service.markRead(2L, 10L, 1L);

    assertEquals(0, result.counted());
    assertNull(result.destroyAt(), "off 策略不返回计时截止时刻");
    assertNull(repository.findByMsgId(10L, "msg-1").get().getDestroyAt());
  }

  @Test
  void earliestDestroyAtReturnsMinActiveDeadlineOrNull() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    repository.save(SecretMessage.restore(1L, 10L, "msg-1", 1L, "cipher-1", 1L, "active",
        now.plusSeconds(60), 1L, now, 1L, now));
    repository.save(SecretMessage.restore(2L, 10L, "msg-2", 1L, "cipher-2", 2L, "active",
        now.plusSeconds(30), 1L, now, 1L, now));
    repository.save(SecretMessage.restore(3L, 10L, "msg-3", 1L, "cipher-3", 3L, "destroyed",
        now.minusSeconds(5), 1L, now, 1L, now));
    SecretMessageApplicationService service = service(repository, policy("30s"));

    assertEquals(now.plusSeconds(30), service.earliestDestroyAt(1L, 10L));

    // 无计时消息的会话返回 null。
    InMemorySecretMessageRepository empty = new InMemorySecretMessageRepository();
    empty.save(SecretMessage.post(10L, "msg-x", 1L, "cipher-x", 1L, 1L, now));
    SecretMessageApplicationService emptyService = service(empty, policy("off"));
    assertNull(emptyService.earliestDestroyAt(1L, 10L));
  }

  @Test
  void destroyedStatesAreIncrementallySyncableFromServer() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyed = new InMemorySecretMessageDestroyedRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    // 两条销毁痕迹（destroyAt 不同）；active 消息不进痕迹表。
    destroyed.items.add(new SecretMessageDestroyed(10L, "msg-1", now.minusSeconds(30), "destroyed"));
    destroyed.items.add(new SecretMessageDestroyed(10L, "msg-2", now.minusSeconds(10), "destroyed"));
    SecretMessageApplicationService service = service(repository, destroyed, policy("30s"),
        new InMemoryOutboxRepository());

    // 首次全量（cursor = MIN）。
    var all = service.listDestroyedStates(1L, 10L, LocalDateTime.MIN, 100);
    assertEquals(2, all.size());
    assertEquals("msg-1", all.getFirst().msgId());
    // 增量：只返回 cursor 之后的销毁状态。
    var incremental = service.listDestroyedStates(1L, 10L, now.minusSeconds(20), 100);
    assertEquals(1, incremental.size());
    assertEquals("msg-2", incremental.getFirst().msgId());
    assertTrue(incremental.getFirst().destroyAt().isAfter(now.minusSeconds(20)));
  }

  @Test
  void destroyExpiredMarksOnlyDueActiveMessagesAsDestroyed() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    LocalDateTime now = LocalDateTime.now();
    SecretMessage due = SecretMessage.restore(1L, 10L, "msg-1", 1L, "cipher-1", 1L, "active",
        now.minusSeconds(5), 1L, now, 1L, now);
    SecretMessage notDue = SecretMessage.restore(2L, 10L, "msg-2", 1L, "cipher-2", 2L, "active",
        now.plusSeconds(30), 1L, now, 1L, now);
    repository.items.add(due);
    repository.items.add(notDue);
    SecretMessageApplicationService service = service(repository, policy("30s"));

    int destroyed = service.destroyExpired(now, 100);

    assertEquals(1, destroyed);
    assertTrue(repository.findByMsgId(10L, "msg-1").isEmpty(), "到期密文应被硬删除");
    assertEquals("active", repository.findByMsgId(10L, "msg-2").get().getStatus());
  }

  @Test
  void destroyExpiredQueuesDestroyedEventForOnlinePush() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    repository.save(SecretMessage.restore(1L, 10L, "msg-1", 1L, "cipher-1", 1L, "active",
        now.minusSeconds(5), 1L, now, 1L, now));
    repository.save(SecretMessage.restore(2L, 10L, "msg-2", 1L, "cipher-2", 2L, "active",
        now.minusSeconds(3), 1L, now, 1L, now));
    SecretMessageApplicationService service = service(repository,
        new InMemorySecretMessageDestroyedRepository(), policy("30s"), outbox);

    int destroyed = service.destroyExpired(now, 100);

    assertEquals(2, destroyed);
    assertEquals(1, outbox.items.size());
    assertEquals(ImMqTopics.SECRET_MESSAGE_DESTROYED_EVENT, outbox.items.getFirst().getTopic());
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("msg-1"));
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("msg-2"));
    assertFalse(outbox.items.getFirst().getPayloadJson().contains("cipher-1"),
        "销毁事件不得包含密文/内容");
  }

  @Test
  void destroyCountdownKeepsEarliestDeadlineWhenReadAgain() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    LocalDateTime now = LocalDateTime.now();
    SecretMessage message = SecretMessage.restore(1L, 10L, "msg-1", 1L, "cipher-1", 1L, "active",
        now.plusSeconds(30), 1L, now, 1L, now);
    repository.items.add(message);
    SecretMessageApplicationService service = service(repository, policy("30s"));

    var result = service.markRead(2L, 10L, 1L);

    assertEquals(0, result.counted(), "已计时的消息重复已读不再重置");
    assertEquals(now.plusSeconds(30), repository.findByMsgId(10L, "msg-1").get().getDestroyAt());
  }

  /**
   * 完整离线序列回归（对应生产事故「消息未查阅就销毁」）：
   * 发送 → 接收方离线未读（destroyAt=null，不销毁）→ 上线拉取（仍不计时）→
   * 显式已读上报（计时开始）→ 到期销毁。
   */
  @Test
  void offlineUnreadThenOnlineReadThenDestroyed() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    SecretMessageApplicationService service = service(repository, policy("30s"));

    // 1. 发送方 A(1) 发送；此刻接收方 B(2) 离线未拉取。
    service.post(1L, new PostSecretMessageCommand(10L, "msg-1", "cipher-1", null));

    // 2. 发送方自己拉取：不触发计时（B 未读，destroyAt 保持 null）。
    List<SecretMessageResult> senderView = service.list(1L, 10L, 0L, 50);
    assertNull(senderView.getFirst().destroyAt(), "对方离线未读时不应开始计时");

    // 3. 接收方 B 上线拉取：仍不计时（拉取≠已读，防止消息还没被查阅就销毁）。
    List<SecretMessageResult> receiverView = service.list(2L, 10L, 0L, 50);
    assertNull(receiverView.getFirst().destroyAt(), "拉取不等于已读，不得开始计时");

    // 4. 接收方 B 显式已读上报（真正看到消息内容）：开始 30s 计时。
    var readResult = service.markRead(2L, 10L, 1L);
    assertEquals(1, readResult.counted());
    LocalDateTime destroyAt = repository.findByMsgId(10L, "msg-1").get().getDestroyAt();
    assertNotNull(destroyAt, "接收方已读后应开始计时");
    assertEquals(readResult.destroyAt(), destroyAt, "已读上报返回的截止时刻应与存储一致");
    assertTrue(destroyAt.isAfter(now) && destroyAt.isBefore(now.plusSeconds(31)),
        "destroyAt 应为已读时刻 +30s（UTC）");

    // 5. 计时到期：调度器销毁（硬删除密文）。
    int destroyed = service.destroyExpired(destroyAt.plusSeconds(1), 100);
    assertEquals(1, destroyed);
    assertTrue(repository.findByMsgId(10L, "msg-1").isEmpty(), "计时到期应硬删除密文");
  }

  @Test
  void recallMarksRecalledAndPublishesRecalledReason() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyed = new InMemorySecretMessageDestroyedRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L,
        LocalDateTime.now(java.time.Clock.systemUTC())));
    SecretMessageApplicationService service = service(repository, destroyed, policy("off"), outbox);

    service.recall(1L, 10L, "msg-1");

    assertTrue(repository.findByMsgId(10L, "msg-1").isEmpty(), "撤回应硬删除密文");
    assertEquals("recalled", destroyed.items.getFirst().reason());
    assertEquals(1, outbox.items.size());
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("\"reason\":\"recalled\""),
        "撤回事件应携带 reason=recalled");
  }

  @Test
  void recallByPeerIsForbidden() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L,
        LocalDateTime.now(java.time.Clock.systemUTC())));
    SecretMessageApplicationService service = service(repository, policy("off"));

    ApiException ex = assertThrows(ApiException.class, () -> service.recall(2L, 10L, "msg-1"));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
  }

  @Test
  void deleteForEveryoneBySenderPublishesDeletedReason() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyed = new InMemorySecretMessageDestroyedRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L,
        LocalDateTime.now(java.time.Clock.systemUTC())));
    SecretMessageApplicationService service = service(repository, destroyed, policy("off"), outbox);

    // 仅发送者（user=1）可删除自己的消息。
    service.deleteForEveryone(1L, 10L, "msg-1");

    assertTrue(repository.findByMsgId(10L, "msg-1").isEmpty(), "删除应硬删除密文");
    assertEquals("deleted", destroyed.items.getFirst().reason());
    assertTrue(outbox.items.getFirst().getPayloadJson().contains("\"reason\":\"deleted\""),
        "删除事件应携带 reason=deleted");
  }

  @Test
  void deleteForEveryoneByPeerAllowed() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyed = new InMemorySecretMessageDestroyedRepository();
    InMemoryOutboxRepository outbox = new InMemoryOutboxRepository();
    repository.save(SecretMessage.post(10L, "msg-1", 1L, "cipher-1", 1L, 1L,
        LocalDateTime.now(java.time.Clock.systemUTC())));
    SecretMessageApplicationService service = service(repository, destroyed, policy("off"), outbox);

    // Telegram 语义：私密聊天双方均可删除任意消息（对端 user=2 可删发送方 user=1 的消息）。
    service.deleteForEveryone(2L, 10L, "msg-1");

    assertTrue(repository.findByMsgId(10L, "msg-1").isEmpty(), "对端删除应硬删除密文");
    assertEquals("deleted", destroyed.items.getFirst().reason());
  }

  @Test
  void recalledStatesSyncCarriesRecalledReason() {
    InMemorySecretMessageRepository repository = new InMemorySecretMessageRepository();
    InMemorySecretMessageDestroyedRepository destroyed = new InMemorySecretMessageDestroyedRepository();
    LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
    destroyed.items.add(new SecretMessageDestroyed(10L, "msg-1", now.minusSeconds(10), "recalled"));
    SecretMessageApplicationService service = service(repository, destroyed, policy("off"),
        new InMemoryOutboxRepository());

    var states = service.listDestroyedStates(1L, 10L, LocalDateTime.MIN, 100);

    assertEquals(1, states.size());
    assertEquals("recalled", states.getFirst().reason());
  }

  private static FeatureTogglePort featureTogglePort() {
    return featureTogglePort(true);
  }

  private static FeatureTogglePort featureTogglePort(boolean secretChatEnabled) {
    return new FeatureTogglePort() {
      @Override public boolean isPrivateChatEnabled() { return true; }
      @Override public boolean isGroupChatEnabled() { return true; }
      @Override public boolean isSecretChatEnabled() { return secretChatEnabled; }
      @Override public boolean isChatDeleteEnabled() { return true; }
    };
  }

  private static SecretChatParticipantPort participants() {
    return new SecretChatParticipantPort() {
      @Override
      public long findPeerUserId(long secretChatId, long senderId) {
        return senderId == 1L ? 2L : 1L;
      }

      @Override
      public List<Long> findParticipants(long secretChatId) {
        return List.of(1L, 2L);
      }
    };
  }

  private static SecretMessageApplicationService service(InMemorySecretMessageRepository repository,
      SecretChatPolicyPort policyPort) {
    return service(repository, new InMemorySecretMessageDestroyedRepository(), policyPort,
        new InMemoryOutboxRepository());
  }

  private static SecretMessageApplicationService service(InMemorySecretMessageRepository repository,
      InMemorySecretMessageDestroyedRepository destroyed, SecretChatPolicyPort policyPort,
      InMemoryOutboxRepository outbox) {
    return new SecretMessageApplicationService(repository, destroyed, policyPort,
        participants(),
        (chatId, msgIds, delaySeconds) -> true,
        outbox,
        null,
        featureTogglePort(),
        new InMemorySecretUnreadProjection(),
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
        NoopUserDeletedMessageRepository.INSTANCE);
  }

  /** 「删除仅我」墓碑仓储的空实现：密聊测试不涉及该过滤。 */
  enum NoopUserDeletedMessageRepository
      implements io.openware.im.message.domain.message.repository.UserDeletedMessageRepository {
    INSTANCE;

    @Override public void markDeleted(long userId, String msgId, String conversationId, String chatType) { }

    @Override public int markDeleted(long userId, List<String> msgIds) { return 0; }

    @Override public List<String> findDeletedMsgIds(long userId, List<String> msgIds) { return List.of(); }

    @Override public List<DeletedMessage> findAllByUserId(long userId) { return List.of(); }

    @Override public void deleteByConversation(long userId, String conversationId) { }
  }

  /** 内存 Outbox：记录事件写入，验证离线推送事件已排队。 */
  static class InMemoryOutboxRepository implements MessageOutboxRepository {
    final List<MessageOutbox> items = new ArrayList<>();

    @Override
    public MessageOutbox save(MessageOutbox outbox) {
      items.add(outbox);
      return outbox;
    }

    @Override
    public List<MessageOutbox> findPending(int batchSize) {
      return items.stream().filter(m -> !m.isPublished()).limit(batchSize).toList();
    }
  }

  static class InMemorySecretUnreadProjection implements io.openware.im.message.domain.message.port.SecretUnreadProjectionPort {
    @Override public void record(String chatType, long conversationId, long userId, String msgId, long seq, LocalDateTime occurredAt) { }
    @Override public void markRead(String chatType, long conversationId, long userId, long afterSeq) { }
    @Override public void deleteByMessage(String chatType, long conversationId, String msgId) { }
    @Override public long countForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) { return 0; }
    @Override public List<ConversationUnread> countByConversationForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) { return List.of(); }
  }

  private static SecretChatPolicyPort policy(String policy) {
    return secretChatId -> policy;
  }

  /** 内存仓库：仅测试用，不泄漏 PO 到应用层。 */
  static class InMemorySecretMessageRepository implements SecretMessageRepository {
    final List<SecretMessage> items = new ArrayList<>();

    @Override
    public SecretMessage save(SecretMessage message) {
      if (message.getId() == null) {
        long id = items.size() + 1L;
        SecretMessage restored = SecretMessage.restore(id, message.getSecretChatId(), message.getMsgId(),
            message.getFromUserId(), message.getCiphertext(), message.getSeq(), message.getStatus(),
            message.getDestroyAt(), message.getCreatedBy(), message.getCreatedAt(),
            message.getUpdatedBy(), message.getUpdatedAt());
        items.add(restored);
        return restored;
      }
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i).getId().equals(message.getId())) {
          items.set(i, message);
          return message;
        }
      }
      items.add(message);
      return message;
    }

    @Override
    public long nextSeq(long secretChatId) {
      return items.stream().filter(m -> m.getSecretChatId().equals(secretChatId))
          .map(SecretMessage::getSeq).max(Comparator.naturalOrder()).orElse(0L) + 1L;
    }

    @Override
    public List<SecretMessage> listAfterSeq(long secretChatId, long afterSeq, int limit) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId) && m.getSeq() > afterSeq)
          .sorted(Comparator.comparing(SecretMessage::getSeq))
          .toList();
    }

    @Override
    public List<SecretMessage> findUncountedReadBy(long secretChatId, long afterSeq, long viewerId) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId))
          .filter(m -> m.getSeq() <= afterSeq)
          .filter(m -> !m.getFromUserId().equals(viewerId))
          .filter(m -> "active".equals(m.getStatus()))
          .filter(m -> m.getDestroyAt() == null)
          .sorted(Comparator.comparing(SecretMessage::getSeq))
          .toList();
    }

    @Override
    public List<SecretMessage> findActiveWithoutDestroyAt(long secretChatId) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId))
          .filter(m -> "active".equals(m.getStatus()))
          .filter(m -> m.getDestroyAt() == null)
          .sorted(Comparator.comparing(SecretMessage::getSeq))
          .toList();
    }

    @Override
    public LocalDateTime findEarliestDestroyAt(long secretChatId) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId))
          .filter(m -> "active".equals(m.getStatus()))
          .filter(m -> m.getDestroyAt() != null)
          .map(SecretMessage::getDestroyAt)
          .min(Comparator.naturalOrder())
          .orElse(null);
    }

    @Override
    public Optional<SecretMessage> findByMsgId(long secretChatId, String msgId) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId) && m.getMsgId().equals(msgId))
          .findFirst();
    }

    @Override
    public int deleteByMsgId(long secretChatId, String msgId) {
      int before = items.size();
      items.removeIf(m -> m.getSecretChatId().equals(secretChatId) && m.getMsgId().equals(msgId));
      return before - items.size();
    }

    @Override
    public int deleteBySecretChatId(long secretChatId) {
      int before = items.size();
      items.removeIf(m -> m.getSecretChatId().equals(secretChatId));
      return before - items.size();
    }

    @Override
    public List<SecretMessage> findActiveByMsgIds(long secretChatId, List<String> msgIds, LocalDateTime now) {
      return items.stream()
          .filter(m -> m.getSecretChatId().equals(secretChatId))
          .filter(m -> msgIds.contains(m.getMsgId()))
          .filter(m -> "active".equals(m.getStatus()))
          .filter(m -> m.getDestroyAt() != null && !m.getDestroyAt().isAfter(now))
          .sorted(Comparator.comparing(SecretMessage::getSeq))
          .toList();
    }

    @Override
    public List<SecretMessage> findExpired(LocalDateTime now, int limit) {
      return items.stream()
          .filter(m -> "active".equals(m.getStatus()))
          .filter(m -> m.getDestroyAt() != null && !m.getDestroyAt().isAfter(now))
          .sorted(Comparator.comparing(SecretMessage::getDestroyAt))
          .toList();
    }

  }

  /** 内存销毁痕迹仓库：仅测试用，记录 msgId + destroyAt + reason（无密文）。 */
  static class InMemorySecretMessageDestroyedRepository implements SecretMessageDestroyedRepository {
    final List<SecretMessageDestroyed> items = new ArrayList<>();

    @Override
    public void save(SecretMessageDestroyed destroyed) {
      items.add(destroyed);
    }

    @Override
    public List<SecretMessageDestroyed> listAfter(long secretChatId, LocalDateTime afterDestroyAt, int limit) {
      return items.stream()
          .filter(d -> d.secretChatId() == secretChatId && d.destroyAt().isAfter(afterDestroyAt))
          .sorted(Comparator.comparing(SecretMessageDestroyed::destroyAt))
          .limit(limit)
          .toList();
    }
  }
}
