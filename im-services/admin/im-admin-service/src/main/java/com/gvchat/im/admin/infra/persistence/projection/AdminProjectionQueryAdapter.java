package com.gvchat.im.admin.infra.persistence.projection;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.admin.application.projection.AdminProjectionQueryPort;
import com.gvchat.im.admin.application.projection.AdminReadProjectionPort;
import com.gvchat.im.admin.infra.persistence.mapper.AdminConversationViewMapper;
import com.gvchat.im.admin.infra.persistence.mapper.AdminMessageViewMapper;
import com.gvchat.im.admin.infra.persistence.mapper.AdminUserViewMapper;
import com.gvchat.im.admin.infra.persistence.mapper.ProjectionEventMapper;
import com.gvchat.im.admin.infra.persistence.po.AdminConversationViewPo;
import com.gvchat.im.admin.infra.persistence.po.AdminMessageViewPo;
import com.gvchat.im.admin.infra.persistence.po.AdminUserViewPo;
import com.gvchat.im.admin.infra.persistence.po.ProjectionEventPo;
import com.gvchat.common.dto.AdminListMessagesDto;
import com.gvchat.common.dto.AdminListUsersDto;
import com.gvchat.common.dto.PageResult;
import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import com.gvchat.common.enums.UserRole;
import com.gvchat.common.enums.UserStatus;
import com.gvchat.im.message.api.admin.AdminMessageResponse;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import com.gvchat.protocol.mq.event.UserStatusChangedEvent;
import com.gvchat.im.user.api.admin.AdminUserResponse;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminProjectionQueryAdapter implements AdminProjectionQueryPort, AdminReadProjectionPort {
  private final AdminUserViewMapper adminUserViewMapper;
  private final AdminMessageViewMapper adminMessageViewMapper;
  private final AdminConversationViewMapper adminConversationViewMapper;
  private final ProjectionEventMapper projectionEventMapper;
  private final ObjectMapper objectMapper;

  @Override
  public PageResult<AdminUserResponse> listUsers(AdminListUsersDto query) {
    int page = page(query.getPage());
    int pageSize = pageSize(query.getPageSize());
    var wrapper = Wrappers.<AdminUserViewPo>lambdaQuery().orderByDesc(AdminUserViewPo::getUpdatedAt);
    if (query.getUsername() != null && !query.getUsername().isBlank()) {
      wrapper.eq(AdminUserViewPo::getUsername, query.getUsername());
    }
    if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
      wrapper.and(value -> value.like(AdminUserViewPo::getUsername, query.getKeyword())
          .or().like(AdminUserViewPo::getNickname, query.getKeyword()));
    }
    if (query.getStatus() != null) {
      wrapper.eq(AdminUserViewPo::getStatus, query.getStatus().getValue());
    }
    Page<AdminUserViewPo> result = adminUserViewMapper.selectPage(new Page<>(page, pageSize), wrapper);
    return PageResult.<AdminUserResponse>builder().items(result.getRecords().stream().map(this::toUser).toList())
        .total(result.getTotal()).page(page).pageSize(pageSize).build();
  }

  @Override
  public PageResult<AdminMessageResponse> listMessages(AdminListMessagesDto query) {
    int page = page(query.getPage());
    int pageSize = pageSize(query.getPageSize());
    var wrapper = Wrappers.<AdminMessageViewPo>lambdaQuery().orderByDesc(AdminMessageViewPo::getCreatedAt);
    if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
      wrapper.like(AdminMessageViewPo::getContent, query.getKeyword());
    }
    if (query.getChatType() != null) {
      wrapper.eq(AdminMessageViewPo::getChatType, query.getChatType().getValue());
    }
    if (query.getMsgType() != null) {
      wrapper.eq(AdminMessageViewPo::getMsgType, query.getMsgType().getValue());
    }
    if (query.getStatus() != null) {
      wrapper.eq(AdminMessageViewPo::getStatus, query.getStatus().getValue());
    }
    if (query.getFromUserId() != null) {
      wrapper.eq(AdminMessageViewPo::getFromUserId, query.getFromUserId());
    }
    Page<AdminMessageViewPo> result = adminMessageViewMapper.selectPage(new Page<>(page, pageSize), wrapper);
    return PageResult.<AdminMessageResponse>builder().items(result.getRecords().stream().map(this::toMessage).toList())
        .total(result.getTotal()).page(page).pageSize(pageSize).build();
  }

  @Override
  public long userCount() { return adminUserViewMapper.selectCount(null); }

  @Override
  public long todayNewUsers() {
    return adminUserViewMapper.selectCount(Wrappers.<AdminUserViewPo>lambdaQuery()
        .ge(AdminUserViewPo::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
  }

  @Override
  public long newUsers(int days) {
    return adminUserViewMapper.selectCount(Wrappers.<AdminUserViewPo>lambdaQuery()
        .ge(AdminUserViewPo::getCreatedAt, since(days)));
  }

  @Override
  public long messageCount() { return adminMessageViewMapper.selectCount(null); }

  @Override
  public long newMessages(int days) {
    return adminMessageViewMapper.selectCount(Wrappers.<AdminMessageViewPo>lambdaQuery()
        .ge(AdminMessageViewPo::getCreatedAt, since(days)));
  }

  @Override
  public long groupCount() {
    return adminConversationViewMapper.selectCount(Wrappers.<AdminConversationViewPo>lambdaQuery()
        .eq(AdminConversationViewPo::getConversationType, "group"));
  }

  @Override
  public long newGroups(int days) {
    return adminConversationViewMapper.selectCount(Wrappers.<AdminConversationViewPo>lambdaQuery()
        .eq(AdminConversationViewPo::getConversationType, "group")
        .ge(AdminConversationViewPo::getCreatedAt, since(days)));
  }

  @Override
  @Transactional
  public void project(UserStatusChangedEvent event) {
    if (!accept(event.getEventId(), "user-status-changed")) {
      return;
    }
    AdminUserViewPo value = adminUserViewMapper.selectById(event.getUserId());
    if (value == null) {
      value = new AdminUserViewPo();
      value.setUserId(event.getUserId());
      value.setStatus(event.getStatus().toLowerCase(java.util.Locale.ROOT));
      value.setStatusVersion(event.getStatusVersion());
      value.setUpdatedAt(localDateTime(event.getOccurredAt()));
      adminUserViewMapper.insert(value);
      return;
    }
    if (value.getStatusVersion() >= event.getStatusVersion()) {
      return;
    }
    value.setStatus(event.getStatus().toLowerCase(java.util.Locale.ROOT));
    value.setStatusVersion(event.getStatusVersion());
    value.setUpdatedAt(localDateTime(event.getOccurredAt()));
    adminUserViewMapper.updateById(value);
  }

  @Override
  @Transactional
  public void project(MessageStoredEvent event) {
    if (!accept(event.getEventId(), "message-stored")) {
      return;
    }
    AdminMessageViewPo value = new AdminMessageViewPo();
    value.setMsgId(event.getMsgId());
    value.setFromUserId(event.getSenderId());
    value.setToId(event.getToId());
    value.setConversationId(event.getConversationId());
    value.setChatType(event.getChatType());
    value.setMsgType(event.getMsgType());
    // 敏感正文/提及不再落库：该投影为只写（列表查询实走 AdminReadClient），content/atUsersJson 属 S3 明文，
    // 冗余存储仅增泄露面，故停止写入（SENSITIVE_DATA_PROTECTION）。
    value.setClientMsgId(event.getClientMsgId());
    value.setReplyMsgId(event.getReplyMsgId());
    value.setStatus("sent");
    value.setCreatedAt(localDateTime(event.getCreatedAt()));
    adminMessageViewMapper.insert(value);
    if (adminConversationViewMapper.selectById(event.getConversationId()) == null) {
      AdminConversationViewPo conversation = new AdminConversationViewPo();
      conversation.setConversationId(event.getConversationId());
      conversation.setConversationType(event.getChatType());
      conversation.setCreatedAt(localDateTime(event.getCreatedAt()));
      conversation.setUpdatedAt(localDateTime(event.getCreatedAt()));
      adminConversationViewMapper.insert(conversation);
    }
  }

  private AdminUserResponse toUser(AdminUserViewPo value) {
    return new AdminUserResponse(value.getUserId(), value.getUsername(), value.getNickname(), value.getAvatar(),
        value.getEmail(), value.getPhone(), value.getSignature(), UserStatus.fromValue(value.getStatus()),
        value.getStatusVersion(), value.getRole() == null ? UserRole.USER : UserRole.fromValue(value.getRole()),
        value.getCreatedAt(), value.getUpdatedAt());
  }

  private AdminMessageResponse toMessage(AdminMessageViewPo value) {
    return new AdminMessageResponse(null, value.getMsgId(), value.getFromUserId(), value.getToId(),
        ChatType.fromValue(value.getChatType()), MsgType.fromValue(value.getMsgType()), value.getContent(),
        value.getClientMsgId(), value.getReplyMsgId(), atUsers(value.getAtUsersJson()),
        MsgStatus.fromValue(value.getStatus()), value.getCreatedAt() == null ? null : value.getCreatedAt().toInstant(java.time.ZoneOffset.UTC));
  }

  private List<String> atUsers(String value) {
    try {
      return value == null ? List.of() : objectMapper.readValue(value, new TypeReference<>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("Invalid message projection atUsersJson", exception);
    }
  }

  private boolean accept(String eventId, String eventType) {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("Projection eventId is required");
    }
    ProjectionEventPo event = new ProjectionEventPo();
    event.setEventId(eventId);
    event.setEventType(eventType);
    event.setProcessedAt(LocalDateTime.now());
    return projectionEventMapper.insertIfAbsent(event) == 1;
  }

  private static int page(Integer value) { return value == null ? 1 : value; }

  private static int pageSize(Integer value) { return value == null ? 20 : value; }

  private static LocalDateTime since(int days) {
    return days == 0 ? LocalDateTime.now().toLocalDate().atStartOfDay() : LocalDateTime.now().minusDays(days);
  }

  private static LocalDateTime localDateTime(java.time.Instant value) {
    return value == null ? LocalDateTime.now() : LocalDateTime.ofInstant(value, java.time.ZoneId.systemDefault());
  }
}
