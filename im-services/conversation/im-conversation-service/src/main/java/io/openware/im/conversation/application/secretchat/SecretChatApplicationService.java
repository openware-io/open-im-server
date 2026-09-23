package io.openware.im.conversation.application.secretchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.conversation.api.secretchat.CreateSecretChatRequest;
import io.openware.im.conversation.api.secretchat.SecretChatResult;
import io.openware.im.conversation.domain.group.repository.ConversationOutboxRepository;
import io.openware.im.conversation.domain.secretchat.model.SecretChat;
import io.openware.im.conversation.domain.secretchat.port.DeviceKeyPort;
import io.openware.im.conversation.domain.secretchat.repository.SecretChatRepository;
import io.openware.protocol.mq.event.SecretChatCreatedEvent;
import io.openware.protocol.mq.event.SecretChatDeletedEvent;
import io.openware.protocol.mq.event.SecretChatDestroyPolicyChangedEvent;
import io.openware.protocol.mq.topic.ImMqTopics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私密聊天应用服务：用例编排 + 参与者鉴权 + 输入校验，握手状态机由领域模型承载。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretChatApplicationService {
  private static final Set<String> DESTROY_POLICIES = Set.of("off", "1s", "2s", "5s", "10s", "30s", "1m", "5m", "1h", "1d", "1w");

  private final SecretChatRepository secretChatRepository;
  private final ConversationOutboxRepository outboxRepository;
  private final DeviceKeyPort deviceKeyPort;
  private final ObjectMapper objectMapper;

  @Transactional
  public SecretChatResult createSecretChat(long userA, CreateSecretChatRequest request) {
    if (request.getUserB() == null) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "userB is required");
    }
    if (userA == request.getUserB()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Cannot create secret chat with yourself");
    }
    long left = Math.min(userA, request.getUserB());
    long right = Math.max(userA, request.getUserB());

    // 一对用户唯一私密会话（与 uk_conversation_secret_chat_users 一致）：已存在时直接复用，
    // 避免对方尚未握手时重复发起触发唯一键冲突报错。
    Optional<SecretChat> existing = secretChatRepository.findBetween(left, right);
    if (existing.isPresent()) {
      SecretChat chat = existing.get();
      String initiatorKey = request.getPublicKey() == null ? "" : request.getPublicKey().trim();
      // 会话未 ready 且本次带发起方公钥时，幂等重试本端握手（补齐本端缺的公钥）。
      if (!initiatorKey.isEmpty() && !"ready".equals(chat.getStatus())) {
        chat = secretChatRepository.save(
            chat.submitHandshake(userA, initiatorKey, userA, LocalDateTime.now(Clock.systemUTC())));
      }
      log.info("Secret chat reused, secretChatId={}, userA={}, userB={}, status={}", chat.getId(), left, right,
          chat.getStatus());
      return toResult(chat, userA);
    }

    LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
    // 预填双方公钥：发起方本端公钥 + 对端服务端已注册设备公钥。
    // 双方齐备即 ready，发起方无需等对方接受/在线即可加密发送（Signal 式模型）。
    String initiatorKey = request.getPublicKey() == null ? "" : request.getPublicKey().trim();
    SecretChat chat;
    if (initiatorKey.isEmpty()) {
      chat = SecretChat.create(left, right, userA, now);
    } else {
      String peerKey = deviceKeyPort.findLatestByUserIds(List.of(request.getUserB())).get(request.getUserB());
      String leftKey = left == userA ? initiatorKey : peerKey;
      String rightKey = right == userA ? initiatorKey : peerKey;
      chat = SecretChat.createWithKeys(left, right, leftKey, rightKey, userA, now);
    }
    SecretChat saved = secretChatRepository.save(chat);
    log.info("Secret chat created, secretChatId={}, userA={}, userB={}, status={}", saved.getId(), left, right,
        saved.getStatus());
    publishCreatedEvent(saved, userA, request.getUserB());
    return toResult(saved, userA);
  }

  /**
   * 同事务发布「私密聊天创建事件」（仅元数据，绝不含密钥/内容），供 im-access-ws 对
   * **对方**推 WS 信号，促使其同步会话列表并完成 E2EE 握手，消除首条消息滞后。
   */
  private void publishCreatedEvent(SecretChat chat, long initiatorUserId, long peerUserId) {
    try {
      String eventId = UUID.randomUUID().toString();
      SecretChatCreatedEvent event = SecretChatCreatedEvent.builder()
          .eventId(eventId)
          .secretChatId(chat.getId())
          .initiatorUserId(initiatorUserId)
          .peerUserId(peerUserId)
          .createdAt(Instant.now(Clock.systemUTC()))
          .build();
      outboxRepository.append(eventId, String.valueOf(chat.getId()), ImMqTopics.SECRET_CHAT_CREATED_EVENT,
          "conv:secret:" + chat.getId(), objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize secret chat created event", ex);
    }
  }

  /** 提交本端公钥参与握手；双方公钥齐备后进入 ready 并计算安全码指纹。 */
  @Transactional
  public SecretChatResult submitHandshake(long userId, long secretChatId, String publicKey) {
    SecretChat chat = requireParticipant(userId, secretChatId);
    SecretChat updated = chat.submitHandshake(userId, publicKey, userId, LocalDateTime.now(Clock.systemUTC()));
    SecretChat saved = secretChatRepository.save(updated);
    log.info("Secret chat handshake, secretChatId={}, userId={}, state={}", secretChatId, userId, saved.getHandshakeState());
    return toResult(saved, userId);
  }

  @Transactional(readOnly = true)
  public SecretChatResult getSecretChat(long userId, long secretChatId) {
    return toResult(requireParticipant(userId, secretChatId), userId);
  }

  @Transactional(readOnly = true)
  public List<SecretChatResult> listMySecretChats(long userId) {
    return secretChatRepository.findByParticipant(userId).stream()
        .map(chat -> toResult(chat, userId)).toList();
  }

  /** 内部接口：返回会话当前销毁策略；会话不存在时按 off（不销毁）处理，避免误伤。 */
  @Transactional(readOnly = true)
  public String destroyPolicyOf(long secretChatId) {
    return secretChatRepository.findById(secretChatId)
        .map(SecretChat::getDestroyPolicy)
        .orElse("off");
  }

  /** 内部接口：返回会话双方参与方（userA/userB），供消息服务计算离线推送接收方。 */
  @Transactional(readOnly = true)
  public Map<String, Long> participantsOf(long secretChatId) {
    SecretChat chat = secretChatRepository.findById(secretChatId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret chat not found"));
    return Map.of("userA", chat.getUserA(), "userB", chat.getUserB());
  }

  /** 删除私密会话：任意一方删除即会话终止（硬删除），双方列表都不再返回。 */
  @Transactional
  public void deleteSecretChat(long userId, long secretChatId) {
    SecretChat chat = requireParticipant(userId, secretChatId);
    long peerUserId = chat.getUserA().equals(userId) ? chat.getUserB() : chat.getUserA();
    secretChatRepository.deleteById(chat.getId());
    publishDeletedEvent(chat, userId, peerUserId);
    log.info("Secret chat deleted, secretChatId={}, userId={}, peerUserId={}", secretChatId, userId, peerUserId);
  }

  /**
   * 同事务发布「私密聊天终止事件」（仅元数据），供消息服务硬删除该会话全部密文、
   * 供 im-access-ws 对对方推 WS 信号以同步移除会话并清空本地消息。
   */
  /**
   * 同事务发布「私密聊天销毁策略变更事件」（仅元数据），供消息服务对历史消息回补销毁计时。
   */
  private void publishDestroyPolicyChangedEvent(SecretChat chat, String policy) {
    try {
      String eventId = UUID.randomUUID().toString();
      SecretChatDestroyPolicyChangedEvent event = SecretChatDestroyPolicyChangedEvent.builder()
          .eventId(eventId)
          .secretChatId(chat.getId())
          .policy(policy)
          .changedAt(Instant.now(Clock.systemUTC()))
          .build();
      outboxRepository.append(eventId, String.valueOf(chat.getId()), ImMqTopics.SECRET_CHAT_DESTROY_POLICY_CHANGED_EVENT,
          "conv:secret:policy:" + chat.getId(), objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize secret chat destroy policy changed event", ex);
    }
  }

  private void publishDeletedEvent(SecretChat chat, long initiatorUserId, long peerUserId) {
    try {
      String eventId = UUID.randomUUID().toString();
      SecretChatDeletedEvent event = SecretChatDeletedEvent.builder()
          .eventId(eventId)
          .secretChatId(chat.getId())
          .initiatorUserId(initiatorUserId)
          .peerUserId(peerUserId)
          .deletedAt(Instant.now(Clock.systemUTC()))
          .build();
      outboxRepository.append(eventId, String.valueOf(chat.getId()), ImMqTopics.SECRET_CHAT_DELETED_EVENT,
          "conv:secret:delete:" + chat.getId(), objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize secret chat deleted event", ex);
    }
  }

  @Transactional
  public SecretChatResult setDestroyPolicy(long userId, long secretChatId, String policy) {
    if (policy == null || !DESTROY_POLICIES.contains(policy)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid destroy policy");
    }
    SecretChat chat = requireParticipant(userId, secretChatId);
    SecretChat saved = secretChatRepository.save(chat.withDestroyPolicy(policy, userId,
        LocalDateTime.now(Clock.systemUTC())));
    publishDestroyPolicyChangedEvent(saved, policy);
    log.info("Secret chat destroy policy set, secretChatId={}, userId={}, policy={}", secretChatId, userId, policy);
    return toResult(saved, userId);
  }

  private SecretChat requireParticipant(long userId, long secretChatId) {
    SecretChat chat = secretChatRepository.findById(secretChatId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Secret chat not found"));
    if (!chat.isParticipant(userId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a participant");
    }
    return chat;
  }

  private SecretChatResult toResult(SecretChat chat, long currentUserId) {
    Long peerUserId = chat.getUserA().equals(currentUserId) ? chat.getUserB() : chat.getUserA();
    return new SecretChatResult(chat.getId(), chat.getUserA(), chat.getUserB(), peerUserId, chat.getStatus(),
        chat.getSafeCode(), chat.getDestroyPolicy(), chat.getUserAPublicKey(), chat.getUserBPublicKey(),
        chat.getHandshakeState(), chat.getCreatedBy(), chat.getCreatedAt(), chat.getUpdatedBy(), chat.getUpdatedAt());
  }
}
