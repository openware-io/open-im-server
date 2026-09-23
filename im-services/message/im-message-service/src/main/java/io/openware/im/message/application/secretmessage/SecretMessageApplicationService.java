package io.openware.im.message.application.secretmessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.message.application.secretmessage.command.PostSecretMessageCommand;
import io.openware.im.message.application.secretmessage.result.SecretMessageResult;
import io.openware.im.message.domain.message.model.MessageOutbox;
import io.openware.im.message.domain.message.port.FeatureTogglePort;
import io.openware.im.message.domain.message.port.SecretUnreadProjectionPort;
import io.openware.im.message.domain.message.repository.MessageOutboxRepository;
import io.openware.im.message.domain.secretmessage.model.SecretMessage;
import io.openware.im.message.domain.secretmessage.model.SecretMessageDestroyed;
import io.openware.im.message.domain.secretmessage.port.SecretChatParticipantPort;
import io.openware.im.message.domain.secretmessage.port.SecretChatPolicyPort;
import io.openware.im.message.domain.secretmessage.port.SecretDestroyDelayedPublisher;
import io.openware.im.message.domain.secretmessage.repository.SecretMessageDestroyedRepository;
import io.openware.im.message.domain.secretmessage.repository.SecretMessageRepository;
import io.openware.im.message.media.MediaReferenceClient;
import io.openware.protocol.mq.event.SecretMessageDestroyedEvent;
import io.openware.protocol.mq.event.SecretMessageStoredEvent;
import io.openware.protocol.mq.topic.ImMqTopics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私密消息应用服务：仅存储密文与元数据，服务端不解密；游标拉取供参与设备同步，定时销毁由 destroyAt 驱动。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretMessageApplicationService {
  private final SecretMessageRepository secretMessageRepository;
  private final SecretMessageDestroyedRepository secretMessageDestroyedRepository;
  private final SecretChatPolicyPort secretChatPolicyPort;
  private final SecretChatParticipantPort secretChatParticipantPort;
  private final SecretDestroyDelayedPublisher secretDestroyDelayedPublisher;
  private final MessageOutboxRepository messageOutboxRepository;
  private final MediaReferenceClient mediaReferences;
  private final FeatureTogglePort featureTogglePort;
  private final SecretUnreadProjectionPort secretUnreadProjectionPort;
  private final ObjectMapper objectMapper;
  private final io.openware.im.message.domain.message.repository.UserDeletedMessageRepository userDeletedMessageRepository;

  @Transactional
  public SecretMessageResult post(long userId, PostSecretMessageCommand command) {
    requireSecretChatEnabled();
    assertParticipant(command.secretChatId(), userId);
    long seq = secretMessageRepository.nextSeq(command.secretChatId());
    SecretMessage saved = secretMessageRepository.save(SecretMessage.post(command.secretChatId(),
        command.msgId(), userId, command.ciphertext(), seq, userId, LocalDateTime.now(Clock.systemUTC())));
    long recipientUserId = secretChatParticipantPort.findPeerUserId(command.secretChatId(), userId);
    if (recipientUserId > 0) {
      secretUnreadProjectionPort.record("secret", command.secretChatId(), recipientUserId, command.msgId(), seq,
          saved.getCreatedAt());
    }
    bindMediaReferences(userId, command);
    log.info("Secret message stored, msgId={}, secretChatId={}, seq={}", command.msgId(),
        command.secretChatId(), seq);
    publishStoredEvent(command, userId, seq);
    return toResult(saved);
  }

  /** 私密消息媒体引用绑定：使接收方可通过消息授权换取新鲜访问 URL（对象内容仍受签名 URL 保护）。 */
  private void bindMediaReferences(long userId, PostSecretMessageCommand command) {
    List<String> objectIds = command.mediaObjectIds();
    if (objectIds == null || objectIds.isEmpty()) return;
    for (String objectId : objectIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
      try {
        mediaReferences.bind(userId, objectId, command.msgId());
      } catch (Exception ex) {
        log.warn("Secret message media bind failed, msgId={}, objectId={}", command.msgId(), objectId, ex);
      }
    }
  }

  /** 私密会话终止时硬删除该会话全部密文（Telegram 语义：删除即销毁双方消息）。 */
  @Transactional
  public void purgeBySecretChatId(long secretChatId) {
    int deleted = secretMessageRepository.deleteBySecretChatId(secretChatId);
    for (long userId : secretChatParticipantPort.findParticipants(secretChatId)) {
      secretUnreadProjectionPort.markRead("secret", secretChatId, userId, Long.MAX_VALUE);
    }
    log.info("Purged secret messages on chat termination, secretChatId={}, deleted={}", secretChatId, deleted);
  }

  /**
   * 密文落库同事务发布「私密消息存储事件」（仅元数据，绝不含密文/内容），
   * 供 im-access-ws 对离线接收方触发推送通知。接收方解析失败时不阻塞发送。
   */
  private void publishStoredEvent(PostSecretMessageCommand command, long senderId, long seq) {
    try {
      long recipientUserId = secretChatParticipantPort.findPeerUserId(command.secretChatId(), senderId);
      if (recipientUserId <= 0) {
        return;
      }
      String eventId = UUID.randomUUID().toString();
      LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
      SecretMessageStoredEvent event = SecretMessageStoredEvent.builder()
          .eventId(eventId)
          .secretChatId(command.secretChatId())
          .msgId(command.msgId())
          .senderId(senderId)
          .recipientUserId(recipientUserId)
          .createdAt(Instant.now(Clock.systemUTC()))
          .build();
      messageOutboxRepository.save(MessageOutbox.pending(eventId, "secret-message", command.msgId(),
          ImMqTopics.SECRET_MESSAGE_STORED_EVENT, "conv:secret:" + command.secretChatId(),
          objectMapper.writeValueAsString(event), now));
      log.info("Secret message stored event queued, msgId={}, secretChatId={}, seq={}, recipientUserId={}",
          command.msgId(), command.secretChatId(), seq, recipientUserId);
    } catch (Exception ex) {
      log.warn("Failed to queue secret message stored event, msgId={}, secretChatId={}, skip offline push",
          command.msgId(), command.secretChatId(), ex);
    }
  }

  /**
   * 游标拉取密文。仅返回消息，不触发任何销毁计时——销毁计时只由接收方
   * 显式上报「已读」（markRead）后开始，杜绝「消息尚未被查阅就被销毁」。
   */
  @Transactional
  public List<SecretMessageResult> list(long viewerId, long secretChatId, long afterSeq, int limit) {
    assertParticipant(secretChatId, viewerId);
    List<SecretMessage> messages = secretMessageRepository.listAfterSeq(secretChatId, afterSeq, limit);
    // 「删除仅我」：该用户删过的密聊消息对其本人不可见（与普通消息共用同一张墓碑表），
    // 因此卸载重装后重新拉取列表也不会把消息“复活”。
    java.util.Set<String> hidden = java.util.Set.copyOf(userDeletedMessageRepository.findDeletedMsgIds(viewerId,
        messages.stream().map(SecretMessage::getMsgId).toList()));
    return messages.stream().filter(message -> !hidden.contains(message.getMsgId()))
        .map(this::toResult).toList();
  }

  private void requireSecretChatEnabled() {
    if (!featureTogglePort.isSecretChatEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Secret chat is disabled");
    }
  }

  /** 重新换取媒体访问 URL（参与方授权）：私密消息媒体 URL 过期后按消息引用换新鲜签名 URL。 */
  public List<Map<String, Object>> accessUrls(long viewerId, long secretChatId, String msgId, List<String> objectIds) {
    secretMessageRepository.findByMsgId(secretChatId, msgId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret message not found"));
    assertParticipant(secretChatId, viewerId);
    if (objectIds == null || objectIds.isEmpty()) return List.of();
    return objectIds.stream().filter(value -> value != null && !value.isBlank()).distinct()
        .map(objectId -> Map.<String, Object>of("objectId", objectId,
            "url", mediaReferences.accessUrl(viewerId, objectId, msgId)))
        .toList();
  }

  /**
   * 接收方已读上报：对 {@code seq <= afterSeq} 且由对方发送、尚未计时的 active
   * 消息按会话销毁策略设置 destroyAt。幂等：已存在的 destroyAt 保持最早截止。
   *
   * <p>只有接收方真正查阅（解密展示）到消息后才应调用；未查阅/未解密成功的消息
   * 不进入计时，避免「消息还没被查阅就销毁」。返回本次计时的截止时刻，供客户端
   * 本地设置销毁定时器（消息销毁事件无推送，客户端须本地主动移除）。</p>
   */
  @Transactional
  public MarkReadResult markRead(long viewerId, long secretChatId, long afterSeq) {
    assertParticipant(secretChatId, viewerId);
    // 已读本身必须清除未读角标；销毁策略仅决定是否启动阅后即焚计时。
    secretUnreadProjectionPort.markRead("secret", secretChatId, viewerId, afterSeq);
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    Duration ttl = ttlOf(secretChatPolicyPort.destroyPolicyOf(secretChatId));
    if (ttl == null) {
      return new MarkReadResult(0, null);
    }
    LocalDateTime destroyAt = now.plus(ttl);
    List<SecretMessage> uncounted = secretMessageRepository.findUncountedReadBy(secretChatId, afterSeq, viewerId);
    int counted = 0;
    for (SecretMessage message : uncounted) {
      secretMessageRepository.save(message.scheduleDestroy(destroyAt, viewerId, now));
      counted++;
    }
    if (counted > 0) {
      log.info("Secret messages read countdown started, secretChatId={}, viewerId={}, afterSeq={}, counted={}",
          secretChatId, viewerId, afterSeq, counted);
      // 推 MQ 延迟消息：ttl 到期由延迟消费者精确销毁这批消息（在线推 WS、离线重入删除）。
      // 超长策略（如 1d）超出 MQ 延迟上限时返回 false，由周期扫描兜底。
      secretDestroyDelayedPublisher.publish(secretChatId,
          uncounted.stream().map(SecretMessage::getMsgId).toList(), (int) ttl.toSeconds());
    }
    return new MarkReadResult(counted, destroyAt);
  }

  /**
   * 延迟销毁命令到期执行：仅销毁仍在 active、且销毁时刻已到（防提前/重复）的
   * [msgIds] 消息；硬删除密文本体 + 登记销毁痕迹 + 发布销毁事件（在线 WS、离线 states）。
   */
  @Transactional
  public int destroyDelayed(long secretChatId, List<String> msgIds, LocalDateTime now) {
    if (msgIds == null || msgIds.isEmpty()) {
      return 0;
    }
    List<SecretMessage> due = secretMessageRepository.findActiveByMsgIds(secretChatId, msgIds, now);
    if (due.isEmpty()) {
      return 0;
    }
    List<SecretMessage> destroyed = new java.util.ArrayList<>();
    for (SecretMessage message : due) {
      if (hardDeleteAndRecord(message, now, "destroyed")) {
        destroyed.add(message);
      }
    }
    if (!destroyed.isEmpty()) {
      publishDestroyedEvent(secretChatId, destroyed, "destroyed");
    }
    log.info("Secret messages destroyed by delayed command, secretChatId={}, msgCount={}", secretChatId, destroyed.size());
    return destroyed.size();
  }

  /** 会话内最早销毁时刻（active 消息中最小 destroyAt）；无计时消息返回 null。 */
  @Transactional(readOnly = true)
  public LocalDateTime earliestDestroyAt(long viewerId, long secretChatId) {
    assertParticipant(secretChatId, viewerId);
    return secretMessageRepository.findEarliestDestroyAt(secretChatId);
  }

  /**
   * 销毁状态增量同步（服务端权威）：从销毁痕迹表返回销毁时刻（destroyAt）晚于
   * [afterDestroyAt] 的 msgId + reason（无密文/内容）。端侧（含离线重连）据此渲染
   * 撤回墓碑或移除本地消息；销毁计时与执行完全由服务端控制，不依赖端侧定时器。
   */
  @Transactional(readOnly = true)
  public List<DestroyedState> listDestroyedStates(long viewerId, long secretChatId, LocalDateTime afterDestroyAt, int limit) {
    assertParticipant(secretChatId, viewerId);
    return secretMessageDestroyedRepository.listAfter(secretChatId, afterDestroyAt, limit).stream()
        .map(destroyed -> new DestroyedState(destroyed.msgId(), destroyed.destroyAt(), destroyed.reason()))
        .toList();
  }

  /** 已销毁消息标识：msgId + 销毁时刻（destroyAt）+ 原因（destroyed/recalled）。 */
  public record DestroyedState(String msgId, LocalDateTime destroyAt, String reason) {
  }

  /** 已读上报结果：counted=本次开始计时的条数；destroyAt=本次计时截止时刻（off 策略为 null）。 */
  public record MarkReadResult(int counted, LocalDateTime destroyAt) {
  }

  /** 定时销毁执行：硬删除已到销毁时间的 active 密文 + 登记痕迹；返回销毁条数。 */
  @Transactional
  public int destroyExpired(LocalDateTime now, int limit) {
    List<SecretMessage> expired = secretMessageRepository.findExpired(now, limit);
    if (expired.isEmpty()) {
      return 0;
    }
    List<SecretMessage> destroyed = new java.util.ArrayList<>();
    for (SecretMessage message : expired) {
      if (hardDeleteAndRecord(message, now, "destroyed")) {
        destroyed.add(message);
      }
    }
    Map<Long, List<SecretMessage>> byChat = destroyed.stream()
        .collect(java.util.stream.Collectors.groupingBy(SecretMessage::getSecretChatId));
    byChat.forEach((chatId, messages) -> publishDestroyedEvent(chatId, messages, "destroyed"));
    log.info("Secret messages destroyed, count={}, now={}", destroyed.size(), now);
    return destroyed.size();
  }

  /**
   * 销毁策略变更回补：对会话内 active 且尚未计时（destroyAt 为空）的历史消息，按
   * 发送时间 + 新策略时长计算销毁时刻——已到期的立即销毁，未到期的补上计时（由周期扫描兜底）。
   */
  @Transactional
  public int applyDestroyPolicy(long secretChatId, String policy) {
    Duration ttl = ttlOf(policy);
    if (ttl == null) {
      return 0;
    }
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    List<SecretMessage> candidates = secretMessageRepository.findActiveWithoutDestroyAt(secretChatId);
    List<SecretMessage> expired = new java.util.ArrayList<>();
    int scheduled = 0;
    for (SecretMessage message : candidates) {
      LocalDateTime candidate = message.getCreatedAt().plus(ttl);
      if (!candidate.isAfter(now)) {
        expired.add(message);
      } else {
        secretMessageRepository.save(message.scheduleDestroy(candidate, 0L, now));
        scheduled++;
      }
    }
    List<SecretMessage> destroyed = new java.util.ArrayList<>();
    for (SecretMessage message : expired) {
      if (hardDeleteAndRecord(message, now, "destroyed")) {
        destroyed.add(message);
      }
    }
    if (!destroyed.isEmpty()) {
      publishDestroyedEvent(secretChatId, destroyed, "destroyed");
    }
    log.info("Secret messages destroy policy applied, secretChatId={}, policy={}, scheduled={}, destroyed={}",
        secretChatId, policy, scheduled, destroyed.size());
    return destroyed.size();
  }

  /** 撤回（仅发送方，不限时）：硬删除密文本体并协调对端渲染撤回墓碑。 */
  @Transactional
  /**
   * 「删除仅我」（密聊）：只把消息对当前用户隐藏，不影响对方，也不销毁密文本体。
   *
   * <p>与 {@link #deleteForEveryone} 的语义区别：后者真正销毁密文并广播对端；
   * 前者只是**该用户自己的可见性**。墓碑落服务端持久表，因此卸载重装后
   * 重新拉取密聊列表也不会把消息“复活”。
   */
  public int deleteForMe(long userId, long secretChatId, List<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) {
      return 0;
    }
    assertParticipant(secretChatId, userId);
    int marked = 0;
    for (String msgId : msgIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList()) {
      if (secretMessageRepository.findByMsgId(secretChatId, msgId).isEmpty()) {
        continue;
      }
      userDeletedMessageRepository.markDeleted(userId, msgId, "conv:secret:" + secretChatId, "secret");
      marked++;
    }
    log.info("Secret messages hidden for user, secretChatId={}, userId={}, marked={}", secretChatId, userId,
        marked);
    return marked;
  }
  public void recall(long userId, long secretChatId, String msgId) {
    assertChatDeleteEnabled();
    SecretMessage message = secretMessageRepository.findByMsgId(secretChatId, msgId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret message not found"));
    if (!message.isActive()) {
      return; // 已销毁/已撤回：幂等
    }
    if (message.getFromUserId() == null || message.getFromUserId() != userId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender can recall");
    }
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    if (hardDeleteAndRecord(message, now, "recalled")) {
      publishDestroyedEvent(secretChatId, List.of(message), "recalled");
      log.info("Secret message recalled, secretChatId={}, msgId={}, userId={}", secretChatId, msgId, userId);
    }
  }

  /** 删除（参与方，不限时）：硬删除密文本体并协调双方移除本地消息。参考 Telegram：私密聊天双方均可删除任意消息。 */
  @Transactional
  public void deleteForEveryone(long userId, long secretChatId, String msgId) {
    assertChatDeleteEnabled();
    SecretMessage message = secretMessageRepository.findByMsgId(secretChatId, msgId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret message not found"));
    if (!message.isActive()) {
      return; // 幂等
    }
    assertParticipant(secretChatId, userId);
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    if (hardDeleteAndRecord(message, now, "deleted")) {
      publishDestroyedEvent(secretChatId, List.of(message), "deleted");
      log.info("Secret message deleted, secretChatId={}, msgId={}, userId={}", secretChatId, msgId, userId);
    }
  }

  /**
   * 硬删除密文消息并登记销毁痕迹；返回 true 表示本调用确实删除了该消息（并发/幂等
   * 场景下仅「删到行」的一方登记痕迹并发布事件，另一方直接忽略）。
   */
  private boolean hardDeleteAndRecord(SecretMessage message, LocalDateTime occurredAt, String reason) {
    int deleted = secretMessageRepository.deleteByMsgId(message.getSecretChatId(), message.getMsgId());
    if (deleted <= 0) {
      return false;
    }
    secretUnreadProjectionPort.deleteByMessage("secret", message.getSecretChatId(), message.getMsgId());
    secretMessageDestroyedRepository.save(
        new SecretMessageDestroyed(message.getSecretChatId(), message.getMsgId(), occurredAt, reason));
    try {
      mediaReferences.unbindByBusiness(message.getMsgId());
    } catch (RuntimeException ex) {
      log.warn("Secret message media unbind failed, msgId={}", message.getMsgId(), ex);
    }
    return true;
  }

  /** 销毁同事务发布「私密消息销毁事件」（仅 msgId，无密文/内容），供在线端主动推送。 */
  private void publishDestroyedEvent(long secretChatId, List<SecretMessage> messages, String reason) {
    try {
      String eventId = UUID.randomUUID().toString();
      LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
      SecretMessageDestroyedEvent event = SecretMessageDestroyedEvent.builder()
          .eventId(eventId)
          .secretChatId(secretChatId)
          .msgIds(messages.stream().map(SecretMessage::getMsgId).toList())
          .participantIds(secretChatParticipantPort.findParticipants(secretChatId))
          .destroyedAt(Instant.now(Clock.systemUTC()))
          .reason(reason)
          .build();
      messageOutboxRepository.save(MessageOutbox.pending(eventId, "secret-message", "destroyed-" + secretChatId,
          ImMqTopics.SECRET_MESSAGE_DESTROYED_EVENT, "conv:secret:" + secretChatId,
          objectMapper.writeValueAsString(event), now));
      log.info("Secret message destroyed event queued, secretChatId={}, msgCount={}",
          secretChatId, messages.size());
    } catch (Exception ex) {
      log.warn("Failed to queue secret message destroyed event, secretChatId={}", secretChatId, ex);
    }
  }

  /** 聊天删除总开关校验：关闭时拒绝用户发起的撤回/删除。 */
  private void assertChatDeleteEnabled() {
    if (!featureTogglePort.isChatDeleteEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Chat deletion is disabled");
    }
  }

  /** 校验 [userId] 为该私密会话参与方（userA/userB），否则拒绝——防止越权读写或触发他人消息销毁。 */
  private void assertParticipant(long secretChatId, long userId) {
    if (!secretChatParticipantPort.findParticipants(secretChatId).contains(Long.valueOf(userId))) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a participant of this secret chat");
    }
  }

  private Duration ttlOf(String policy) {
    if (policy == null) {
      return null;
    }
    return switch (policy) {
      case "1s" -> Duration.ofSeconds(1);
      case "2s" -> Duration.ofSeconds(2);
      case "5s" -> Duration.ofSeconds(5);
      case "10s" -> Duration.ofSeconds(10);
      case "30s" -> Duration.ofSeconds(30);
      case "1m" -> Duration.ofMinutes(1);
      case "5m" -> Duration.ofMinutes(5);
      case "1h" -> Duration.ofHours(1);
      case "1d" -> Duration.ofDays(1);
      case "1w" -> Duration.ofDays(7);
      default -> null;
    };
  }

  private SecretMessageResult toResult(SecretMessage message) {
    return new SecretMessageResult(message.getId(), message.getSecretChatId(), message.getMsgId(),
        message.getFromUserId(), message.getCiphertext(), message.getSeq(), message.getStatus(),
        message.getDestroyAt(), message.getCreatedAt());
  }
}
