package com.gvchat.im.message.infra.projection;

import lombok.extern.slf4j.Slf4j;

import com.gvchat.im.message.domain.message.event.MessageEditedEvent;
import com.gvchat.im.message.domain.message.port.UnreadCountProjectionPort;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MessageProjectionService implements com.gvchat.im.message.domain.message.port.HotMessageProjectionPort {
  private final MongoTemplate mongoTemplate;
  private final UnreadCountProjectionPort unreadCountProjectionPort;
  private final Duration hotMessageTtl;

  public MessageProjectionService(
      MongoTemplate mongoTemplate,
      UnreadCountProjectionPort unreadCountProjectionPort,
      @Value("${im.message.projection.hot-ttl-days:30}") long hotTtlDays) {
    this.mongoTemplate = mongoTemplate;
    this.unreadCountProjectionPort = unreadCountProjectionPort;
    this.hotMessageTtl = Duration.ofDays(hotTtlDays);
  }

  @PostConstruct
  void ensureIndexes() {
    // 索引初始化只做性能优化，MongoDB 暂时不可达/超时不应阻断服务启动；
    // 失败仅告警，后续写入仍会失败重试（由 MongoTemplate 底层驱动处理）。
    try {
      mongoTemplate.indexOps(HotMessageDocument.class)
          .createIndex(new Index().on("conversationId", Sort.Direction.ASC).on("seq", Sort.Direction.ASC));
      mongoTemplate.indexOps(HotMessageDocument.class)
          .createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
      log.info("Initialized Mongo hot message projection indexes.");
    } catch (RuntimeException ex) {
      log.warn("Failed to initialize Mongo hot message projection indexes, will retry lazily: {}", ex.getMessage());
    }
  }

  public void project(MessageStoredEvent event) {
    log.info(
        "Projecting stored message event, eventId={}, msgId={}, conversationId={}, chatType={}",
        event.getEventId(),
        event.getMsgId(),
        event.getConversationId(),
        event.getChatType());
    var expiresAt = event.getCreatedAt().plus(hotMessageTtl);
    mongoTemplate.save(HotMessageDocument.builder()
        .id(event.getMsgId())
        .conversationId(event.getConversationId())
        .seq(event.getSeq())
        .msgId(event.getMsgId())
        .senderId(event.getSenderId())
        .senderUsername(event.getSenderUsername())
        .clientMsgId(event.getClientMsgId())
        .chatType(event.getChatType())
        .toId(event.getToId())
        .msgType(event.getMsgType())
        .content(event.getContent())
        .replyMsgId(event.getReplyMsgId())
        .atUsersJson(event.getAtUsersJson())
        .createdAt(event.getCreatedAt())
        .expiresAt(expiresAt)
        .build());

    // 新消息使所有接收方的未读计数失效，下次查询按 MySQL 权威重算（覆盖单聊/群聊/频道）。
    invalidateUnreadForRecipients(event.getRecipientUserIds(), event.getChatType(), event.getToId());
    log.debug("Stored group hot message projection, msgId={}, expiresAt={}", event.getMsgId(), expiresAt);
  }

  /** 编辑事件：同步更新 MongoDB 热消息投影的正文与 edited 标记（历史/离线回读仍以 MySQL 权威为准）。 */
  public void projectEdit(MessageEditedEvent event) {
    HotMessageDocument existing = mongoTemplate.findById(event.msgId(), HotMessageDocument.class);
    if (existing == null) {
      log.warn("Hot message projection missing on edit, msgId={}", event.msgId());
      return;
    }
    existing.setContent(event.content());
    existing.setEdited(true);
    existing.setEditedAt(event.editedAt());
    mongoTemplate.save(existing);
    log.info("Updated hot message projection on edit, msgId={}, conversationId={}",
        event.msgId(), event.conversationId());
  }

  /** 消息被撤回/删除：移除对应热消息，保持与 MySQL 权威数据一致。 */
  @Override
  public void deleteByMsgId(String msgId) {
    if (msgId == null || msgId.isBlank()) return;
    try {
      var removed = mongoTemplate.remove(
          org.springframework.data.mongodb.core.query.Query.query(
              org.springframework.data.mongodb.core.query.Criteria.where("_id").is(msgId)),
          HotMessageDocument.class);
      log.info("Removed hot message projection, msgId={}, removed={}", msgId, removed.getDeletedCount());
    } catch (RuntimeException ex) {
      // 投影清理失败不应影响删除主流程（MySQL 才是权威）。
      log.warn("Failed to remove hot message projection, msgId={}, error={}", msgId, ex.toString());
    }
  }

  /** 会话被清空：移除该会话下全部热消息。 */
  @Override
  public void deleteByConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) return;
    try {
      var removed = mongoTemplate.remove(
          org.springframework.data.mongodb.core.query.Query.query(
              org.springframework.data.mongodb.core.query.Criteria.where("conversationId").is(conversationId)),
          HotMessageDocument.class);
      log.info("Removed hot message projections of conversation, conversationId={}, removed={}",
          conversationId, removed.getDeletedCount());
    } catch (RuntimeException ex) {
      log.warn("Failed to remove hot message projections, conversationId={}, error={}",
          conversationId, ex.toString());
    }
  }

  private void invalidateUnreadForRecipients(java.util.List<Long> recipientUserIds, String chatType, String toId) {
    if (recipientUserIds != null && !recipientUserIds.isEmpty()) {
      for (Long recipientUserId : recipientUserIds) {
        unreadCountProjectionPort.invalidate(recipientUserId);
      }
      return;
    }
    if ("private".equals(chatType)) {
      unreadCountProjectionPort.invalidate(Long.parseLong(toId));
    }
  }
}
