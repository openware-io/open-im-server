package com.gvchat.im.message.application.secretgroupmessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand;
import com.gvchat.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand.RecipientCiphertext;
import com.gvchat.im.message.application.secretgroupmessage.result.SecretGroupMessageResult;
import com.gvchat.im.message.domain.message.model.MessageOutbox;
import com.gvchat.im.message.domain.message.port.FeatureTogglePort;
import com.gvchat.im.message.domain.message.port.SecretUnreadProjectionPort;
import com.gvchat.im.message.domain.message.repository.MessageOutboxRepository;
import com.gvchat.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import com.gvchat.im.message.domain.secretgroupmessage.model.SecretGroupMessageDestroyed;
import com.gvchat.im.message.domain.secretgroupmessage.repository.SecretGroupMessageDestroyedRepository;
import com.gvchat.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import com.gvchat.im.message.domain.secretgroupmessage.port.SecretGroupChatPort;
import com.gvchat.im.message.media.MediaReferenceClient;
import com.gvchat.protocol.mq.event.SecretGroupMessageStoredEvent;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 私密群聊消息应用服务：逐成员存密文（服务端不解密）+ 游标拉取 + 存储事件（供 access-ws 推送）
 * + 阅后即焚（已读后计时 + 周期扫描）+ 撤回/删除协调（服务端权威销毁痕迹）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretGroupMessageApplicationService {
  private final SecretGroupMessageRepository messageRepository;
  private final SecretGroupMessageDestroyedRepository destroyedRepository;
  private final SecretGroupChatPort secretGroupChatPort;
  private final MessageOutboxRepository messageOutboxRepository;
  private final MediaReferenceClient mediaReferences;
  private final FeatureTogglePort featureTogglePort;
  private final SecretUnreadProjectionPort secretUnreadProjectionPort;
  private final ObjectMapper objectMapper;
  private final com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository userDeletedMessageRepository;

  @Transactional
  public List<SecretGroupMessageResult> post(long senderId, PostSecretGroupMessageCommand command) {
    requireSecretGroupChatEnabled();
    if (command.secretGroupId() == null || command.msgId() == null || command.msgId().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "secretGroupId and msgId are required");
    }
    if (command.recipients() == null || command.recipients().isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "recipients are required");
    }
    assertMember(senderId, command.secretGroupId());
    requireCanPost(senderId, command.secretGroupId());
    long seq = messageRepository.nextSeq(command.secretGroupId());
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    List<SecretGroupMessageResult> results = new ArrayList<>();
    List<Long> recipientUserIds = new ArrayList<>();
    for (RecipientCiphertext recipient : command.recipients()) {
      if (recipient.userId() == null || recipient.userId() <= 0) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid recipient user id");
      }
      if (recipient.userId() == senderId) {
        continue; // 发送方本端不存密文（本地已有明文）
      }
      if (recipient.ciphertext() == null || recipient.ciphertext().isBlank()) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "ciphertext is required");
      }
      SecretGroupMessage saved = messageRepository.save(SecretGroupMessage.post(
          command.secretGroupId(), command.msgId(), senderId, recipient.userId(), recipient.ciphertext(), seq, now));
      secretUnreadProjectionPort.record("secret_group", command.secretGroupId(), recipient.userId(), command.msgId(),
          seq, now);
      results.add(toResult(saved));
      recipientUserIds.add(recipient.userId());
    }
    if (!recipientUserIds.isEmpty()) {
      publishStoredEvent(command.secretGroupId(), command.msgId(), senderId, recipientUserIds,
          command.atUserIds(), now);
    }
    bindMediaReferences(senderId, command);
    log.info("Secret group message stored, groupId={}, msgId={}, seq={}, recipientCount={}",
        command.secretGroupId(), command.msgId(), seq, recipientUserIds.size());
    return results;
  }

  /** 游标拉取本端密文（仅返回接收方为自己、active 的消息）。 */
  @Transactional(readOnly = true)
  public List<SecretGroupMessageResult> list(long viewerId, long secretGroupId, long afterSeq, int limit) {
    assertMember(viewerId, secretGroupId);
    List<SecretGroupMessage> messages = messageRepository.listAfterSeq(secretGroupId, viewerId, afterSeq, limit);
    // 「删除仅我」：该成员删过的密群消息对其本人不可见（与普通消息共用同一张墓碑表），
    // 因此卸载重装后重新拉取也不会把消息“复活”。
    java.util.Set<String> hidden = java.util.Set.copyOf(userDeletedMessageRepository.findDeletedMsgIds(viewerId,
        messages.stream().map(SecretGroupMessage::getMsgId).toList()));
    return messages.stream().filter(message -> !hidden.contains(message.getMsgId()))
        .map(this::toResult).toList();
  }

  /** 私密群聊媒体引用绑定：使接收方可通过消息授权换取新鲜访问 URL（对象内容仍受签名 URL 保护）。 */
  private void bindMediaReferences(long senderId, PostSecretGroupMessageCommand command) {
    List<String> objectIds = command.mediaObjectIds();
    if (objectIds == null || objectIds.isEmpty()) return;
    for (String objectId : objectIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
      try {
        mediaReferences.bind(senderId, objectId, command.msgId());
      } catch (Exception ex) {
        log.warn("Secret group message media bind failed, msgId={}, objectId={}", command.msgId(), objectId, ex);
      }
    }
  }

  /** 重新换取媒体访问 URL（参与方授权）：私密群聊媒体 URL 过期后按消息引用换新鲜签名 URL。 */
  public List<Map<String, Object>> accessUrls(long viewerId, long secretGroupId, String msgId, List<String> objectIds) {
    if (!secretGroupChatPort.findParticipants(secretGroupId).contains(viewerId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a participant of this secret group");
    }
    if (objectIds == null || objectIds.isEmpty()) return List.of();
    return objectIds.stream().filter(value -> value != null && !value.isBlank()).distinct()
        .map(objectId -> Map.<String, Object>of("objectId", objectId,
            "url", mediaReferences.accessUrl(viewerId, objectId, msgId)))
        .toList();
  }

  /** 编辑已发消息（仅发送方）：保留原 seq 与 msgId，逐成员替换密文并重置销毁计时。 */
  @Transactional
  public List<SecretGroupMessageResult> edit(long senderId, PostSecretGroupMessageCommand command) {
    if (command.secretGroupId() == null || command.msgId() == null || command.msgId().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "secretGroupId and msgId are required");
    }
    if (command.recipients() == null || command.recipients().isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "recipients are required");
    }
    assertMember(senderId, command.secretGroupId());
    requireCanPost(senderId, command.secretGroupId());
    SecretGroupMessage existing = messageRepository.findByMsgId(command.secretGroupId(), command.msgId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret group message not found"));
    if (existing.getFromUserId() == null || existing.getFromUserId() != senderId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender can edit");
    }
    long seq = existing.getSeq();
    // 编辑会重建逐接收方密文；先清除旧投影，避免已移除接收者继续显示未读。
    secretUnreadProjectionPort.deleteByMessage("secret_group", command.secretGroupId(), command.msgId());
    messageRepository.deleteByMsgId(command.secretGroupId(), command.msgId());
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    List<SecretGroupMessageResult> results = new ArrayList<>();
    List<Long> recipientUserIds = new ArrayList<>();
    for (RecipientCiphertext recipient : command.recipients()) {
      if (recipient.userId() == null || recipient.userId() <= 0) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid recipient user id");
      }
      if (recipient.userId() == senderId) {
        continue;
      }
      if (recipient.ciphertext() == null || recipient.ciphertext().isBlank()) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "ciphertext is required");
      }
      SecretGroupMessage saved = messageRepository.save(SecretGroupMessage.post(
          command.secretGroupId(), command.msgId(), senderId, recipient.userId(), recipient.ciphertext(), seq, now));
      secretUnreadProjectionPort.record("secret_group", command.secretGroupId(), recipient.userId(), command.msgId(),
          seq, now);
      results.add(toResult(saved));
      recipientUserIds.add(recipient.userId());
    }
    if (!recipientUserIds.isEmpty()) {
      publishStoredEvent(command.secretGroupId(), command.msgId(), senderId, recipientUserIds,
          command.atUserIds(), now);
    }
    bindMediaReferences(senderId, command);
    log.info("Secret group message edited, groupId={}, msgId={}, seq={}", command.secretGroupId(), command.msgId(), seq);
    return results;
  }

  /**
   * 接收方已读上报：对 {@code seq <= afterSeq} 且由对方发送、尚未计时的 active 消息按群销毁策略
   * 设置 destroyAt。幂等：已存在的 destroyAt 保持最早截止。返回截止时刻供客户端设置本地销毁定时器。
   */
  @Transactional
  public MarkReadResult markRead(long viewerId, long secretGroupId, long afterSeq) {
    assertMember(viewerId, secretGroupId);
    // 已读本身必须清除未读角标；销毁策略仅决定是否启动阅后即焚计时。
    secretUnreadProjectionPort.markRead("secret_group", secretGroupId, viewerId, afterSeq);
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    Duration ttl = ttlOf(secretGroupChatPort.destroyPolicyOf(secretGroupId));
    if (ttl == null) {
      return new MarkReadResult(0, null);
    }
    LocalDateTime destroyAt = now.plus(ttl);
    List<SecretGroupMessage> uncounted = messageRepository.findUncountedReadBy(secretGroupId, viewerId, afterSeq, viewerId);
    int counted = 0;
    for (SecretGroupMessage message : uncounted) {
      messageRepository.save(message.scheduleDestroy(destroyAt));
      counted++;
    }
    if (counted > 0) {
      log.info("Secret group messages read countdown started, secretGroupId={}, viewerId={}, afterSeq={}, counted={}",
          secretGroupId, viewerId, afterSeq, counted);
    }
    return new MarkReadResult(counted, destroyAt);
  }

  /**
   * 周期扫描销毁：硬删除已到销毁时间的 active 密文（逐接收方）。自毁不写销毁痕迹——
   * 端侧靠 markRead/list 返回的 destroyAt 本地计时移除，重新拉取时被删除的 seq 自然消失。
   */
  @Transactional
  public int destroyExpired(LocalDateTime now, int limit) {
    List<SecretGroupMessage> expired = messageRepository.findExpired(now, limit);
    if (expired.isEmpty()) {
      return 0;
    }
    int destroyed = 0;
    for (SecretGroupMessage message : expired) {
      int deleted = messageRepository.deleteByMsgIdAndRecipient(
          message.getSecretGroupId(), message.getMsgId(), message.getRecipientUserId());
      if (deleted > 0) {
        secretUnreadProjectionPort.deleteByMessage("secret_group", message.getSecretGroupId(), message.getMsgId());
        destroyed++;
      }
    }
    if (destroyed > 0) {
      log.info("Secret group messages destroyed, count={}", destroyed);
    }
    return destroyed;
  }

  /** 销毁状态增量同步（服务端权威）：返回销毁时刻晚于 [afterDestroyAt] 的 msgId + reason（撤回/删除）。 */
  @Transactional(readOnly = true)
  public List<DestroyedState> listDestroyedStates(long viewerId, long secretGroupId, LocalDateTime afterDestroyAt, int limit) {
    assertMember(viewerId, secretGroupId);
    return destroyedRepository.listAfter(secretGroupId, afterDestroyAt, limit).stream()
        .map(destroyed -> new DestroyedState(destroyed.msgId(), destroyed.destroyAt(), destroyed.reason()))
        .toList();
  }

  /** 撤回（仅发送方，不限时）：硬删除全体成员的该条密文并登记销毁痕迹（reason=recalled）。 */
  @Transactional
  /**
   * 「删除仅我」（密群）：只把消息对当前用户隐藏，不影响其他成员，也不销毁密文本体。
   *
   * <p>与 deleteForEveryone 的区别：后者真正销毁密文并广播全群；前者只是该用户自己的可见性。
   * 墓碑落服务端持久表，卸载重装后重新拉取也不会把消息“复活”。
   */
  public int deleteForMe(long userId, long secretGroupId, java.util.List<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) {
      return 0;
    }
    assertMember(userId, secretGroupId);
    int marked = 0;
    for (String msgId : msgIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList()) {
      if (messageRepository.findByMsgId(secretGroupId, msgId).isEmpty()) {
        continue;
      }
      userDeletedMessageRepository.markDeleted(userId, msgId, "conv:secret_group:" + secretGroupId, "secret_group");
      marked++;
    }
    log.info("Secret group messages hidden for user, secretGroupId={}, userId={}, marked={}", secretGroupId,
        userId, marked);
    return marked;
  }
  public void recall(long userId, long secretGroupId, String msgId) {
    assertChatDeleteEnabled();
    Optional<SecretGroupMessage> found = messageRepository.findByMsgId(secretGroupId, msgId);
    if (found.isEmpty()) {
      return; // 已销毁/已撤回：幂等
    }
    if (found.get().getFromUserId() == null || found.get().getFromUserId() != userId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender can recall");
    }
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    int deleted = messageRepository.deleteByMsgId(secretGroupId, msgId);
    if (deleted > 0) {
      secretUnreadProjectionPort.deleteByMessage("secret_group", secretGroupId, msgId);
      destroyedRepository.save(new SecretGroupMessageDestroyed(secretGroupId, msgId, now, "recalled"));
      try {
        mediaReferences.unbindByBusiness(msgId);
      } catch (RuntimeException ex) {
        log.warn("Secret group message media unbind failed on recall, msgId={}", msgId, ex);
      }
      log.info("Secret group message recalled, secretGroupId={}, msgId={}, userId={}", secretGroupId, msgId, userId);
    }
  }

  /** 删除（发送方或群主，不限时）：硬删除全体成员的该条密文并登记销毁痕迹（reason=deleted）。参考 Telegram：群主可删除任意成员消息。 */
  @Transactional
  public void deleteForEveryone(long userId, long secretGroupId, String msgId) {
    assertChatDeleteEnabled();
    Optional<SecretGroupMessage> found = messageRepository.findByMsgId(secretGroupId, msgId);
    if (found.isEmpty()) {
      return; // 已销毁/已删除：幂等
    }
    if ((found.get().getFromUserId() == null || found.get().getFromUserId() != userId)
        && secretGroupChatPort.ownerOf(secretGroupId) != userId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender or group owner can delete");
    }
    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    int deleted = messageRepository.deleteByMsgId(secretGroupId, msgId);
    if (deleted > 0) {
      destroyedRepository.save(new SecretGroupMessageDestroyed(secretGroupId, msgId, now, "deleted"));
      try {
        mediaReferences.unbindByBusiness(msgId);
      } catch (RuntimeException ex) {
        log.warn("Secret group message media unbind failed on delete, msgId={}", msgId, ex);
      }
      log.info("Secret group message deleted, secretGroupId={}, msgId={}, userId={}", secretGroupId, msgId, userId);
    }
  }

  /** 聊天删除总开关校验：关闭时拒绝用户发起的撤回/删除。 */
  private void assertChatDeleteEnabled() {
    if (!featureTogglePort.isChatDeleteEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Chat deletion is disabled");
    }
  }

  /** 校验 [userId] 为该私密群成员，否则拒绝——防止非成员投递/读取/触发销毁。 */
  private void assertMember(long userId, long secretGroupId) {
    if (!secretGroupChatPort.findParticipants(secretGroupId).contains(Long.valueOf(userId))) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a member of this secret group");
    }
  }

  private void requireCanPost(long senderId, long secretGroupId) {
    if (secretGroupChatPort.isOwnerOnlyPost(secretGroupId) && secretGroupChatPort.ownerOf(secretGroupId) != senderId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only owner can post in this group");
    }
  }

  private void requireSecretGroupChatEnabled() {
    if (!featureTogglePort.isSecretGroupChatEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Secret group chat is disabled");
    }
  }

  private void publishStoredEvent(long secretGroupId, String msgId, long senderId, List<Long> recipientUserIds,
      List<Long> atUserIds, LocalDateTime now) {
    try {
      String eventId = UUID.randomUUID().toString();
      SecretGroupMessageStoredEvent event = SecretGroupMessageStoredEvent.builder()
          .eventId(eventId)
          .secretGroupId(secretGroupId)
          .msgId(msgId)
          .senderId(senderId)
          .recipientUserIds(recipientUserIds)
          .atUserIds(atUserIds)
          .createdAt(Instant.now(Clock.systemUTC()))
          .build();
      messageOutboxRepository.save(MessageOutbox.pending(eventId, "secret-group-message", msgId,
          ImMqTopics.SECRET_GROUP_MESSAGE_STORED_EVENT, "conv:secret-group:" + secretGroupId,
          objectMapper.writeValueAsString(event), now));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize secret group message stored event", ex);
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

  private SecretGroupMessageResult toResult(SecretGroupMessage message) {
    return new SecretGroupMessageResult(message.getId(), message.getSecretGroupId(), message.getMsgId(),
        message.getFromUserId(), message.getRecipientUserId(), message.getCiphertext(), message.getSeq(),
        message.getStatus(), message.getDestroyAt(), message.getCreatedAt());
  }

  /** 已销毁消息标识：msgId + 销毁时刻 + 原因（recalled/deleted）。 */
  public record DestroyedState(String msgId, LocalDateTime destroyAt, String reason) {
  }

  /** 已读上报结果：counted=本次开始计时的条数；destroyAt=本次计时截止时刻（off 策略为 null）。 */
  public record MarkReadResult(int counted, LocalDateTime destroyAt) {
  }
}
