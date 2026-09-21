package com.gvchat.im.message.application;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.constant.AppConstants;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.enums.MsgType;
import com.gvchat.im.message.application.command.ClearGroupChatCommand;
import com.gvchat.im.message.application.command.ClearPrivateChatCommand;
import com.gvchat.im.message.application.command.DeleteMessageCommand;
import com.gvchat.im.message.application.command.EditMessageCommand;
import com.gvchat.im.message.application.command.MarkMessagesReadCommand;
import com.gvchat.im.message.application.command.StoreMessageCommand;
import com.gvchat.im.message.application.query.MessageHistoryQuery;
import com.gvchat.im.message.application.query.MessageSyncQuery;
import com.gvchat.im.message.application.query.SearchMessagesQuery;
import com.gvchat.im.message.application.result.DeleteMessageResult;
import com.gvchat.im.message.application.result.EditMessageResult;
import com.gvchat.im.message.application.result.ConversationUnreadResult;
import com.gvchat.im.message.application.result.AdminMessagePageResult;
import com.gvchat.im.message.application.result.AdminMessageStatsResult;
import com.gvchat.im.message.application.result.MarkMessagesReadResult;
import com.gvchat.im.message.application.result.MessageResult;
import com.gvchat.im.message.application.result.MessageSyncResult;
import com.gvchat.im.message.application.result.SearchMessagesResult;
import com.gvchat.im.message.application.result.SyncedMessageResult;
import com.gvchat.im.message.domain.message.model.Message;
import com.gvchat.im.message.domain.message.model.Mention;
import com.gvchat.im.message.domain.message.model.MessageOutbox;
import com.gvchat.im.message.domain.message.model.MessageReadStatus;
import com.gvchat.im.message.domain.message.model.UserSyncIndex;
import com.gvchat.im.message.domain.message.event.MessageEditedEvent;
import com.gvchat.im.message.domain.message.port.ConversationSequencePort;
import com.gvchat.im.message.domain.message.port.FriendRelationPort;
import com.gvchat.im.message.domain.message.port.GroupMembershipPort;
import com.gvchat.im.message.domain.message.port.MentionResolverPort;
import com.gvchat.im.message.domain.message.port.ChannelMembershipPort;
import com.gvchat.im.message.domain.message.port.FeatureTogglePort;
import com.gvchat.im.message.domain.message.port.MessagePayloadCodecPort;
import com.gvchat.im.message.domain.message.port.SecretUnreadProjectionPort;
import com.gvchat.im.message.domain.message.port.UnreadCountProjectionPort;
import com.gvchat.im.message.domain.message.port.UserMuteStatusPort;
import com.gvchat.im.message.domain.message.repository.MessageOutboxRepository;
import com.gvchat.im.message.domain.message.repository.MessageReadStatusRepository;
import com.gvchat.im.message.domain.message.repository.MessageFavoriteRepository;
import com.gvchat.im.message.domain.message.repository.MessageRepository;
import com.gvchat.im.message.domain.message.repository.UserSyncIndexRepository;
import com.gvchat.im.message.domain.moderation.ModerationWordFilter;
import com.gvchat.im.message.domain.moderation.ModerationWordSource;
import com.gvchat.im.message.domain.message.port.AdminMessageTotalCountPort;
import com.gvchat.im.message.media.MediaReferenceClient;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.mq.event.MessageMedia;
import com.gvchat.protocol.mq.event.ChatClearedEvent;
import com.gvchat.protocol.mq.support.ConversationIds;
import com.gvchat.protocol.mq.event.MessageRecalledEvent;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageApplicationService {
  private final MessageRepository messageRepository;
  private final MessageReadStatusRepository messageReadStatusRepository;
  private final UserSyncIndexRepository userSyncIndexRepository;
  private final MessageOutboxRepository messageOutboxRepository;
  private final ConversationSequencePort conversationSequencePort;
  private final MessagePayloadCodecPort messagePayloadCodecPort;
  private final FriendRelationPort friendRelationPort;
  private final GroupMembershipPort groupMembershipPort;
  private final MentionResolverPort mentionResolverPort;
  private final ChannelMembershipPort channelMembershipPort;
  private final UnreadCountProjectionPort unreadCountProjectionPort;
  private final SecretUnreadProjectionPort secretUnreadProjectionPort;
  private final MediaReferenceClient mediaReferences;
  private final ModerationWordSource moderationWordSource;
  private final UserMuteStatusPort userMuteStatusPort;
  private final FeatureTogglePort featureTogglePort;
  private final AdminMessageTotalCountPort adminMessageTotalCountCache;
  private final MessageFavoriteRepository favoriteRepository;
  private final UserDeletedMessageRepository userDeletedMessageRepository;
  private final com.gvchat.im.message.domain.message.repository.UserConversationClearRepository userConversationClearRepository;
  private final com.gvchat.im.message.domain.message.port.HotMessageProjectionPort hotMessageProjectionPort;

  @Transactional
  public Message store(StoreMessageCommand command) {
    var existing = messageRepository.findByMsgId(command.commandId());
    if (existing.isEmpty() && command.clientMsgId() != null && !command.clientMsgId().isBlank()) {
      existing = messageRepository.findBySenderIdAndClientMsgId(command.senderId(), command.clientMsgId());
    }
    return existing.orElseGet(() -> storeNew(command));
  }

  @Transactional
  public MarkMessagesReadResult markRead(MarkMessagesReadCommand command) {
    if (command.msgIds() == null || command.msgIds().isEmpty()) {
      return new MarkMessagesReadResult(0);
    }
    Set<String> ownedMessageIds = userSyncIndexRepository.findOwnedMessageIds(command.userId(), command.msgIds());
    LocalDateTime readAt = utcNow();
    int inserted = 0;
    for (String msgId : command.msgIds()) {
      if (ownedMessageIds.contains(msgId)
          && messageReadStatusRepository.saveIfAbsent(MessageReadStatus.mark(msgId, command.userId(), readAt))) {
        inserted++;
      }
    }
    if (inserted > 0) {
      unreadCountProjectionPort.invalidate(command.userId());
    }
    return new MarkMessagesReadResult(inserted);
  }

  @Transactional(readOnly = true)
  public long getUnreadCount(long userId) {
    long count = messageRepository.countUnreadForUser(userId, featureTogglePort.isPrivateChatEnabled(),
        featureTogglePort.isGroupChatEnabled(), featureTogglePort.isChannelEnabled())
        + secretUnreadProjectionPort.countForUser(userId, featureTogglePort.isSecretChatEnabled(),
            featureTogglePort.isSecretGroupChatEnabled());
    unreadCountProjectionPort.replace(userId, count);
    return count;
  }

  /** 按会话未读数（服务端权威，直接从 MySQL 聚合，不经 Redis 缓存）：供客户端角标按会话展示。 */
  @Transactional(readOnly = true)
  public List<ConversationUnreadResult> getUnreadByConversation(long userId) {
    List<ConversationUnreadResult> ordinary = messageRepository.countUnreadByConversationForUser(userId, featureTogglePort.isPrivateChatEnabled(),
        featureTogglePort.isGroupChatEnabled(), featureTogglePort.isChannelEnabled()).stream()
        .map(item -> new ConversationUnreadResult(item.conversationId(), item.count()))
        .toList();
    List<ConversationUnreadResult> secret = secretUnreadProjectionPort.countByConversationForUser(userId,
        featureTogglePort.isSecretChatEnabled(), featureTogglePort.isSecretGroupChatEnabled()).stream()
        .map(item -> new ConversationUnreadResult(item.conversationId(), item.count())).toList();
    return java.util.stream.Stream.concat(ordinary.stream(), secret.stream()).toList();
  }

  /**
   * 编辑消息正文：仅发送者本人、发送后 2 分钟内可编辑（含已被对方读的消息，类似 Telegram）。
   * <p>编辑只改正文并置 edited 标记，不重新分配 seq、不改写同步索引（已读状态保留）。
   * 通过 Outbox 发布 MessageEditedEvent，由 Relay 同步 MongoDB 热消息投影并投递 MQ，
   * 接入层据此下行更新到发送者其它设备与接收方设备（历史/离线走同步索引回读已编辑正文）。
   */
  @Transactional
  public EditMessageResult editMessage(EditMessageCommand command) {
    if (command.newContent() == null || command.newContent().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Edited content is required");
    }
    Message message = messageRepository.findByMsgId(command.msgId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Message not found"));
    if (message.getFromUserId() != command.userId()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender can edit");
    }
    LocalDateTime now = utcNow();
    if (!message.editableAt(now)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Edit window expired");
    }
    String content = command.newContent();
    ModerationWordFilter.Result moderation = ModerationWordFilter.filter(content, moderationWordSource.enabledWords());
    if (moderation.blocked()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Edited content contains sensitive words");
    }
    content = moderation.content();
    Message edited = message.edited(content, command.userId(), now);
    messageRepository.update(edited);
    List<Long> recipientUserIds = new java.util.ArrayList<>(
        userSyncIndexRepository.findRecipientUserIdsByMsgId(message.getMsgId()));
    if (!recipientUserIds.contains(message.getFromUserId())) {
      recipientUserIds.add(message.getFromUserId());
    }
    String eventId = java.util.UUID.randomUUID().toString();
    MessageEditedEvent event = new MessageEditedEvent(eventId, edited.getMsgId(), edited.getConversationId(),
        edited.getSeq(), edited.getFromUserId(), edited.getChatType().getValue(), edited.getToId(),
        edited.getContent(), recipientUserIds, now.toInstant(ZoneOffset.UTC));
    messageOutboxRepository.save(MessageOutbox.pending(eventId, "message", edited.getMsgId(),
        MessageEditedEvent.TOPIC, edited.getConversationId(),
        messagePayloadCodecPort.writeMessageEditedEvent(event), now));
    log.info("消息已编辑, msgId={}, conversationId={}, seq={}, editorId={}",
        edited.getMsgId(), edited.getConversationId(), edited.getSeq(), command.userId());
    return new EditMessageResult(edited.getMsgId(), edited.getConversationId(), edited.getChatType().getValue(),
        edited.getToId(), edited.getFromUserId(), true, now.toInstant(ZoneOffset.UTC));
  }

  @Transactional(readOnly = true)
  public List<MessageResult> getHistory(MessageHistoryQuery query) {
    assertCanAccessHistory(query.userId(), query.peerId(), query.chatType());
    if (query.centerMsgId() != null && !query.centerMsgId().isBlank()) {
      // 以中心消息定位窗口（推送点击/搜索跳转的锚点场景）。
      return messageRepository.findCentered(query.userId(), query.peerId(), query.chatType(), query.centerMsgId(),
          query.beforeCount() == null ? 30 : query.beforeCount(),
          query.afterCount() == null ? 30 : query.afterCount()).stream()
          .map(message -> toResult(message, query.userId())).toList();
    }
    if (query.date() != null && !query.date().isBlank()) {
      // 按日期（yyyy-MM-dd，UTC）过滤某一天的消息。
      LocalDate date = parseDate(query.date());
      return messageRepository.findHistoryByDate(query.userId(), query.peerId(), query.chatType(), date,
          page(query.page()), pageSize(query.pageSize())).stream()
          .map(message -> toResult(message, query.userId())).toList();
    }
    return messageRepository.findHistory(query.userId(), query.peerId(), query.chatType(), page(query.page()),
        pageSize(query.pageSize())).stream().map(message -> toResult(message, query.userId())).toList();
  }

  /** 会话内「有消息」的日期（yyyy-MM-dd，UTC，倒序），用于聊天记录日历标记。 */
  @Transactional(readOnly = true)
  public List<String> getHistoryDates(MessageHistoryQuery query) {
    assertCanAccessHistory(query.userId(), query.peerId(), query.chatType());
    return messageRepository.findHistoryDates(query.userId(), query.peerId(), query.chatType());
  }

  @Transactional(readOnly = true)
  public SearchMessagesResult search(SearchMessagesQuery query) {
    if (query.chatType() == null || query.peerId() == null || query.peerId().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Message search requires chat type and peer identifier");
    }
    assertCanAccessHistory(query.userId(), query.peerId(), query.chatType());
    int page = page(query.page());
    int pageSize = pageSize(query.pageSize());
    MessageRepository.MessagePage result = messageRepository.search(query.userId(), query.keyword(), query.peerId(),
        query.chatType(), query.msgType(), page, pageSize);
    return new SearchMessagesResult(result.items().stream().map(message -> toResult(message, query.userId())).toList(),
        result.total(), page, pageSize);
  }

  @Transactional(readOnly = true)
  public AdminMessagePageResult getAdminPage(Integer page, Integer pageSize) {
    int resolvedPage = page(page);
    int resolvedPageSize = pageSize(pageSize);
    long total = cachedTotalMessageCount();
    List<Message> items = messageRepository.findAdminPage(resolvedPage, resolvedPageSize);
    return new AdminMessagePageResult(items.stream().map(message -> toResult(message, message.getFromUserId())).toList(), total,
        resolvedPage, resolvedPageSize);
  }

  /** 管理端消息总数：优先走 60 秒缓存，未命中时 COUNT 并回填，避免每次分页都全表 COUNT。 */
  private long cachedTotalMessageCount() {
    Long cached = adminMessageTotalCountCache.get();
    if (cached != null) return cached;
    long total = messageRepository.count();
    adminMessageTotalCountCache.put(total);
    return total;
  }

  @Transactional(readOnly = true)
  public AdminMessageStatsResult getAdminStats(int days) {
    LocalDateTime since = days <= 0
        ? LocalDate.now(Clock.systemUTC()).atStartOfDay()
        : utcNow().minusDays(days);
    return new AdminMessageStatsResult(messageRepository.count(),
        messageRepository.countCreatedSince(since),
        messageRepository.dailyCounts(since),
        messageRepository.countDistinctSendersSince(since));
  }

  @Transactional(readOnly = true)
  public MessageSyncResult sync(MessageSyncQuery query) {
    int limit = Math.min(query.limit() == null ? 200 : query.limit(), 500);
    List<UserSyncIndex> indexes = userSyncIndexRepository.findAfter(query.userId(), query.afterSyncSeq(), limit + 1);
    boolean hasMore = indexes.size() > limit;
    List<UserSyncIndex> page = hasMore ? indexes.subList(0, limit) : indexes;
    Map<String, Message> messages = new HashMap<>();
    for (Message message : messageRepository.findByMsgIds(page.stream().map(UserSyncIndex::msgId).toList())) {
      messages.put(message.getMsgId(), message);
    }
    // 「删除仅我」的消息不再下发给该用户：否则卸载重装/换端后会重新出现。
    Set<String> userDeleted = Set.copyOf(
        userDeletedMessageRepository.findDeletedMsgIds(query.userId(), new ArrayList<>(messages.keySet())));
    Map<String, LocalDateTime> readAts = messageReadStatusRepository.findReadAtByUserIdAndMsgIds(query.userId(),
        messages.keySet());
    List<SyncedMessageResult> items = page.stream().filter(index -> messages.containsKey(index.msgId()))
        .filter(index -> !userDeleted.contains(index.msgId()))
        .filter(index -> canSynchronize(query.userId(), messages.get(index.msgId())))
        .map(index -> new SyncedMessageResult(index.syncSeq(), toResult(messages.get(index.msgId()), query.userId()),
            readAts.get(index.msgId())))
        .toList();
    long nextSyncSeq = page.isEmpty() ? query.afterSyncSeq() : page.getLast().syncSeq();
    return new MessageSyncResult(items, nextSyncSeq, hasMore, clearedConversations(query.userId()),
        deletedMessages(query.userId()));
  }

  /**
   * 「删除仅我」：把消息标记为对当前用户不可见。
   *
   * <p>不删除消息本体、不影响其它成员；墓碑持久化在服务端，因此卸载重装后
   * 重新同步也不会把消息“复活”（客户端本地隐藏列表随重装丢失，这正是此前的缺陷）。
   */
  @Transactional
  public int deleteForMe(long userId, List<String> msgIds) {
    assertChatDeleteEnabled();
    if (msgIds == null || msgIds.isEmpty()) {
      return 0;
    }
    List<String> normalized = msgIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    if (normalized.isEmpty()) {
      return 0;
    }
    Map<String, Message> owned = new HashMap<>();
    for (Message message : messageRepository.findByMsgIds(normalized)) {
      owned.put(message.getMsgId(), message);
    }
    if (owned.isEmpty()) {
      return 0;
    }
    int marked = 0;
    for (Message message : owned.values()) {
      if (!canSynchronize(userId, message)) {
        // 该用户本就看不到这条消息（无权限），无需也不应写入墓碑。
        continue;
      }
      userDeletedMessageRepository.markDeleted(userId, message.getMsgId(), message.getConversationId(),
          message.getChatType().name());
      marked++;
    }
    if (marked > 0) {
      // 未读口径随之变化，失效投影（缓存失效后按 MySQL 权威重算）。
      unreadCountProjectionPort.invalidate(userId);
    }
    return marked;
  }

  @Transactional
  public void clearPrivateChat(ClearPrivateChatCommand command) {
    assertChatDeleteEnabled();
    long peerUserId = parseId(command.peerId());
    if (!friendRelationPort.areFriends(command.userId(), peerUserId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not friends");
    }
    messageRepository.deletePrivateChat(command.userId(), peerUserId);
    hotMessageProjectionPort.deleteByConversationId(ConversationIds.privateConversation(command.userId(), peerUserId));
    unreadCountProjectionPort.invalidate(command.userId());
    unreadCountProjectionPort.invalidate(peerUserId);
    String conversationId = ConversationIds.privateConversation(command.userId(), peerUserId);
    // 双方都写持久清空标记：实时 WS 通知在**对方离线**时必然丢失
    // （WsBroadcastService 走 Redis 房间广播，无订阅者也返回成功），
    // 消息又已被删除、增量同步不会告知删除，对方本地旧记录会一直残留。
    // 有了标记，客户端每次同步都能据此自愈（离线/换端/重装均覆盖）。
    LocalDateTime clearedAt = utcNow();
    userConversationClearRepository.markCleared(command.userId(), conversationId, ChatType.PRIVATE.getValue(),
        clearedAt, command.userId());
    userConversationClearRepository.markCleared(peerUserId, conversationId, ChatType.PRIVATE.getValue(),
        clearedAt, command.userId());
    // 通知对方：服务端记录已清空，对方端缓存/本地库必须一并清除（否则对方界面照旧显示，
    // 出现「服务端已删、对方还看得到」的三层不一致）。在线时这条通知能让对端立即刷新。
    publishChatCleared(ChatType.PRIVATE, conversationId, String.valueOf(peerUserId), command.userId(),
        List.of(peerUserId));
  }

  @Transactional
  public void clearGroupChat(ClearGroupChatCommand command) {
    assertChatDeleteEnabled();
    long groupId = parseId(command.groupId());
    if (!groupMembershipPort.isMember(groupId, command.userId())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not group member");
    }
    messageRepository.deleteGroupChat(groupId);
    hotMessageProjectionPort.deleteByConversationId("conv:group:" + groupId);
    // 清空群聊删除群内全部消息，所有成员未读计数均需重算（缓存失效后按 MySQL 权威重算）。
    List<Long> members = groupMembershipPort.findMemberUserIds(groupId);
    for (Long memberId : members) {
      unreadCountProjectionPort.invalidate(memberId);
    }
    // 通知除发起人外的全体群成员清理本地记录。
    List<Long> recipients = members.stream().filter(id -> id != command.userId()).distinct().toList();
    if (!recipients.isEmpty()) {
      publishChatCleared(ChatType.GROUP, "conv:group:" + groupId, String.valueOf(groupId), command.userId(),
          recipients);
    }
    // 持久清空标记：所有成员各写一条，离线成员下次同步时据此清理本地记录。
    LocalDateTime groupClearedAt = utcNow();
    for (Long memberId : members) {
      userConversationClearRepository.markCleared(memberId, "conv:group:" + groupId, ChatType.GROUP.getValue(),
          groupClearedAt, command.userId());
    }
  }

  /** 「删除仅我」墓碑（含会话定位）：客户端据此删除本地副本并刷新会话预览。 */
  private List<MessageSyncResult.DeletedMessageResult> deletedMessages(long userId) {
    return userDeletedMessageRepository.findAllByUserId(userId).stream()
        .map(item -> new MessageSyncResult.DeletedMessageResult(item.msgId(), item.conversationId(),
            item.chatType()))
        .toList();
  }

  /**
   * 该用户名下的会话清空标记：客户端每次同步都拉取，据此删除本地早于 clearedAt 的消息。
   * 这是离线场景的唯一补救（实时 WS 通知在对方离线时必然丢失，且消息已删、增量同步不会告知删除）。
   */
  private List<MessageSyncResult.ClearedConversationResult> clearedConversations(long userId) {
    return userConversationClearRepository.findByUserId(userId).stream()
        .map(cleared -> new MessageSyncResult.ClearedConversationResult(cleared.conversationId(),
            cleared.chatType(), cleared.clearedAt()))
        .toList();
  }

  /** 写入「会话记录被清空」事件到 outbox，由 MQ 广播给其余成员清理端侧数据。 */
  private void publishChatCleared(ChatType chatType, String conversationId, String targetId, long clearedByUserId,
      List<Long> recipientUserIds) {
    var occurredAt = utcNow();
    String eventId = java.util.UUID.randomUUID().toString();
    ChatClearedEvent event = new ChatClearedEvent(eventId, 1, occurredAt.toInstant(ZoneOffset.UTC),
        chatType.getValue(), conversationId, targetId, clearedByUserId, recipientUserIds, "cleared");
    messageOutboxRepository.save(MessageOutbox.pending(eventId, "message", conversationId,
        ImMqTopics.MESSAGE_CHAT_CLEARED_EVENT, conversationId,
        messagePayloadCodecPort.writeChatClearedEvent(event), occurredAt));
  }

  @Transactional
  public DeleteMessageResult deleteForEveryone(DeleteMessageCommand command) {
    assertChatDeleteEnabled();
    Message message = messageRepository.findByMsgId(command.msgId()).orElse(null);
    // 幂等：消息已被硬删除（MQ 至少一次投递 / 重复点击 / 并发撤回删除）直接视为成功。
    if (message == null) {
      return new DeleteMessageResult(true, command.msgId(), ChatType.PRIVATE, "", 0L);
    }
    assertCanDelete(command.userId(), message, command.recall());
    return hardDeleteMessage(message, command.userId(), command.recall() ? "recalled" : "deleted");
  }

  /** 管理端删除任意消息（举报处理联动「删消息」）：跳过发送方校验，硬删并广播 deleted 事件。 */
  @Transactional
  public DeleteMessageResult adminDeleteMessage(String msgId) {
    Message message = messageRepository.findByMsgId(msgId).orElse(null);
    if (message == null) {
      return new DeleteMessageResult(true, msgId, ChatType.PRIVATE, "", 0L);
    }
    return hardDeleteMessage(message, message.getFromUserId(), "deleted");
  }

  /** 聊天删除总开关校验：关闭时拒绝用户发起的删除/撤回/清空（管理端删除不受此开关限制）。 */
  private void assertChatDeleteEnabled() {
    if (!featureTogglePort.isChatDeleteEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Chat deletion is disabled");
    }
  }

  private DeleteMessageResult hardDeleteMessage(Message message, long operatorId, String reason) {
    LocalDateTime occurredAt = utcNow();
    // 撤回与删除业务语义一致：服务端痕迹硬删除（消息本体 + 同步索引 + 已读状态），
    // 仅客户端呈现不同（撤回→墓碑、删除→移除），由事件 reason 区分。
    List<Long> recipientUserIds = userSyncIndexRepository.findRecipientUserIdsByMsgId(message.getMsgId());
    userSyncIndexRepository.deleteByMsgId(message.getMsgId());
    messageReadStatusRepository.deleteByMsgId(message.getMsgId());
    messageRepository.deleteByMsgId(message.getMsgId());
    favoriteRepository.deleteByMsgId(message.getMsgId());
    // Mongo 热消息投影此前**只写不清**：撤回/删除后仍保留（30 天 TTL 才消失），
    // 与 MySQL 权威数据不一致；一旦将来有读取方（历史加速/搜索/离线回捞）会读到已删内容。
    hotMessageProjectionPort.deleteByMsgId(message.getMsgId());
    // 撤回/删除贯通媒体清理：解除消息对媒体对象的引用，避免稳定匿名 URL 在删除后仍可下载。
    for (String objectId : message.getMediaObjectIds()) {
      try {
        mediaReferences.unbind(objectId, message.getMsgId());
      } catch (RuntimeException ex) {
        log.warn("Message media unbind failed on hard delete, msgId={}, objectId={}",
            message.getMsgId(), objectId, ex);
      }
    }
    String eventId = java.util.UUID.randomUUID().toString();
    MessageRecalledEvent event = new MessageRecalledEvent(eventId, 1, occurredAt.toInstant(ZoneOffset.UTC),
        message.getMsgId(), message.getConversationId(), message.getChatType().getValue(), message.getToId(),
        operatorId, recipientUserIds, reason);
    messageOutboxRepository.save(MessageOutbox.pending(eventId, "message", message.getMsgId(),
        ImMqTopics.MESSAGE_RECALLED_EVENT, message.getConversationId(),
        messagePayloadCodecPort.writeMessageRecalledEvent(event), occurredAt));
    // 硬删除后所有接收方未读计数需重算（缓存失效触发重算）。
    for (Long recipientUserId : recipientUserIds) {
      unreadCountProjectionPort.invalidate(recipientUserId);
    }
    return new DeleteMessageResult(true, message.getMsgId(), message.getChatType(), message.getToId(), message.getFromUserId());
  }

  private Message storeNew(StoreMessageCommand command) {
    if (userMuteStatusPort.isMuted(command.senderId())) {
      log.warn("Blocked message from globally muted user, senderId={}, conversationId={}",
          command.senderId(), command.conversationId());
      return null;
    }
    List<String> mediaObjectIds = normalizeMediaObjectIds(command.mediaObjectIds(), MsgType.fromValue(command.msgType()));
    if ((command.content() == null || command.content().isBlank()) && mediaObjectIds.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Message content or media is required");
    }
    String content = command.content();
    if (content != null && !content.isBlank()) {
      // 内容审核：high 拦截（不落库）、medium 替换、low 仅记录。
      ModerationWordFilter.Result moderation = ModerationWordFilter.filter(content, moderationWordSource.enabledWords());
      if (moderation.blocked()) {
        log.warn("Blocked message containing high-level sensitive word, senderId={}, conversationId={}",
            command.senderId(), command.conversationId());
        return null;
      }
      content = moderation.content();
      if (!moderation.matches().isEmpty()) {
        log.info("Sensitive word matched, msgId={}, matches={}", command.commandId(), moderation.matches());
      }
    }
    ChatType chatType = ChatType.fromValue(command.chatType());
    if (chatType == ChatType.PRIVATE && !featureTogglePort.isPrivateChatEnabled()) {
      log.warn("Blocked private message because privateChatEnabled is off, senderId={}", command.senderId());
      return null;
    }
    if (chatType == ChatType.GROUP && !featureTogglePort.isGroupChatEnabled()) {
      log.warn("Blocked group message because groupChatEnabled is off, senderId={}", command.senderId());
      return null;
    }
    if (chatType == ChatType.CHANNEL && !featureTogglePort.isChannelEnabled()) {
      log.warn("Blocked channel message because channelEnabled is off, senderId={}", command.senderId());
      return null;
    }
    List<Long> recipientUserIds = recipientUserIds(command, chatType);
    long seq = conversationSequencePort.next(command.conversationId());
    LocalDateTime createdAt = LocalDateTime.ofInstant(command.acceptedAt(), ZoneOffset.UTC);
    Message message = Message.create(command.commandId(), command.conversationId(), seq, command.senderId(),
        command.senderUsername(), command.toId(), chatType,
        MsgType.fromValue(command.msgType()), content, command.clientMsgId(), command.replyMsgId(),
        resolveMentions(chatType, command.toId(), command.atUsersJson()), mediaObjectIds, createdAt);
    messageRepository.save(message);
    Map<Long, Long> recipientSyncSeqs = synchronizeUsers(message, recipientUserIds);
    String mediaKind = mediaKind(message.getMsgType());
    for (String objectId : mediaObjectIds) {
      mediaReferences.authorizeAndBind(command.senderId(), objectId, message.getMsgId(), mediaKind);
    }
    List<MessageMedia> media = mediaObjectIds.stream().map(objectId -> new MessageMedia(objectId,
        mediaReferences.accessUrl(command.senderId(), objectId, message.getMsgId()))).toList();
    messageOutboxRepository.save(MessageOutbox.pending(message.getMsgId(), "message", message.getMsgId(),
        ImMqTopics.MESSAGE_STORED_EVENT, message.getConversationId(),
        messagePayloadCodecPort.writeStoredMessageEvent(message, recipientUserIds, recipientSyncSeqs, media), utcNow()));
    log.info("权威消息与 Outbox 事件已同事务落库, msgId={}, conversationId={}, seq={}",
        message.getMsgId(), message.getConversationId(), message.getSeq());
    return message;
  }

  /** 频道消息游标拉取：订阅者/所有者按 seq 增量同步（进房读历史；新消息通知走 per-user 同步索引 + WS/JPush）。 */
  @Transactional(readOnly = true)
  public List<MessageResult> listChannelMessages(long userId, long channelId, long afterSeq, int limit) {
    if (!channelMembershipPort.isOwner(channelId, userId)
        && !channelMembershipPort.isSubscribed(channelId, userId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not subscribed to channel");
    }
    String conversationId = com.gvchat.protocol.mq.support.ConversationIds.channelConversation(channelId);
    return messageRepository.findChannelMessages(conversationId, afterSeq, limit).stream()
        .map(message -> toResult(message, userId)).toList();
  }

  private LocalDateTime utcNow() {
    return LocalDateTime.now(Clock.systemUTC());
  }

  private List<Long> recipientUserIds(StoreMessageCommand command, ChatType chatType) {
    long targetId = parseId(command.toId());
    if (isSystemMessage(command)) {
      // 系统消息仅由内部服务发出：senderId 恒为 0（真实用户 id 恒为正），
      // 不经过用户成员/禁言校验；非 0 发送方一律拒绝，杜绝普通用户伪造 system 消息。
      if (command.senderId() != 0L) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "System messages are internal only");
      }
      if (chatType == ChatType.PRIVATE) {
        // 私聊系统提示（如「你已添加了XXX，现在可以开始聊天了」）：只发给 toId 指定的接收方，
        // 客户端按「居中灰色系统提示」渲染，不作为任何一方的聊天气泡（对齐微信）。
        return List.of(targetId);
      }
      if (chatType != ChatType.GROUP) {
        throw new ApiException(HttpStatusCodes.BAD_REQUEST, "System messages are only supported in groups");
      }
      return groupMembershipPort.findMemberUserIds(targetId).stream().distinct().toList();
    }
    if (chatType == ChatType.PRIVATE) {
      if (!friendRelationPort.areFriends(command.senderId(), targetId)) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not friends");
      }
      return List.of(targetId);
    }
    if (chatType == ChatType.CHANNEL) {
      if (!channelMembershipPort.isOwner(targetId, command.senderId())) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only channel owner can publish");
      }
      // 订阅者即通知接收方（排除发布者本人）；用于 WS 实时推送 + 离线 JPush + per-user 同步索引。
      return channelMembershipPort.listSubscriberUserIds(targetId).stream()
          .filter(userId -> userId != command.senderId()).distinct().toList();
    }
    GroupMembershipPort.GroupMessageRecipientSnapshot snapshot =
        groupMembershipPort.messageRecipientSnapshot(targetId, command.senderId());
    if (!snapshot.senderAllowed()) {
      // 区分「已解散」与「非成员」：群解散后聊天记录保留但不可再发消息（参考微信），
      // 若统一报「Not a group member」会让用户以为被踢了，提示误导。
      String reason = snapshot.denialCode();
      if (reason != null && reason.contains("UNAVAILABLE")) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "Group is dissolved, no more messages allowed");
      }
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not a group member");
    }
    return snapshot.memberUserIds().stream().filter(userId -> userId != command.senderId()).distinct().toList();
  }

  /**
   * 解析 @ 目标：群聊按「群昵称优先→个人昵称」解析，并支持 @all（解析为特殊目标 0）；
   * 单聊/频道与空列表原样透传（客户端已解析为具体用户 id）。
   */
  private List<String> resolveMentions(ChatType chatType, String toId, String atUsersJson) {
    List<String> raw = messagePayloadCodecPort.readAtUsers(atUsersJson);
    if (raw.isEmpty() || chatType != ChatType.GROUP) {
      return raw;
    }
    MentionResolverPort.MentionResolution resolution = mentionResolverPort.resolveGroupMentions(parseId(toId), raw);
    if (resolution == null) {
      return raw;
    }
    List<String> resolved = new java.util.ArrayList<>(
        resolution.resolvedUserIds().stream().map(String::valueOf).toList());
    if (resolution.mentionsAll()) {
      resolved.add(Mention.AT_ALL);
    }
    return resolved.stream().distinct().toList();
  }

  private boolean isSystemMessage(StoreMessageCommand command) {
    return MsgType.SYSTEM == MsgType.fromValue(command.msgType());
  }

  private Map<Long, Long> synchronizeUsers(Message message, List<Long> recipientUserIds) {
    List<Long> userIds = new java.util.ArrayList<>(recipientUserIds);
    userIds.add(message.getFromUserId());
    // 系统消息发送方为 user 0（非真实用户），不为其写 per-user 同步索引。
    userIds = userIds.stream().filter(id -> id > 0).distinct().sorted().toList();
    if (message.getChatType() == ChatType.GROUP && userIds.size() > 500) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Group capacity exceeded");
    }
    Map<Long, Long> sequences = new HashMap<>();
    for (Long userId : userIds) {
      sequences.put(userId, userSyncIndexRepository.nextSequence(userId));
    }
    userSyncIndexRepository.saveAll(userIds.stream().map(userId -> new UserSyncIndex(userId, sequences.get(userId),
        message.getMsgId(), message.getConversationId(), message.getCreatedAt())).toList());
    return Map.copyOf(sequences);
  }

  private int page(Integer page) {
    return page != null ? page : 1;
  }

  private int pageSize(Integer pageSize) {
    return Math.min(pageSize != null ? pageSize : AppConstants.DEFAULT_PAGE_SIZE, AppConstants.MAX_PAGE_SIZE);
  }

  private long parseId(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid identifier");
    }
  }

  private LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (Exception ex) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid date");
    }
  }

  private void assertCanAccessHistory(long userId, String peerId, ChatType chatType) {
    boolean allowed;
    if (chatType == ChatType.PRIVATE) {
      allowed = friendRelationPort.areFriends(userId, parseId(peerId));
    } else if (chatType == ChatType.CHANNEL) {
      long channelId = parseId(peerId);
      allowed = channelMembershipPort.isOwner(channelId, userId)
          || channelMembershipPort.isSubscribed(channelId, userId);
    } else {
      allowed = groupMembershipPort.isMember(parseId(peerId), userId);
    }
    if (!allowed) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not allowed to access history");
    }
  }

  private boolean canSynchronize(long userId, Message message) {
    return message.getChatType() != ChatType.GROUP
        || groupMembershipPort.isMember(parseId(message.getToId()), userId);
  }

  /**
   * 删除/撤回权限（参考 Telegram 语义）：
   * - 发送方始终可撤回/删除自己的消息；
   * - 撤回（墓碑）仅发送方；
   * - 删除他人消息：单聊对端、群聊群主/管理员可删（delete for everyone）。
   */
  private void assertCanDelete(long userId, Message message, boolean recall) {
    if (message.getFromUserId() == userId) {
      return;
    }
    if (recall) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only sender can recall");
    }
    ChatType chatType = message.getChatType();
    if (chatType == ChatType.PRIVATE) {
      // 单聊：toId 为对端 userId，对端可删除对方消息。
      if (message.getToId() != null && parseId(message.getToId()) == userId) {
        return;
      }
    } else if (chatType == ChatType.GROUP) {
      // 群聊：群主/管理员可删除任意成员消息。
      if (message.getToId() != null
          && groupMembershipPort.isOwnerOrAdmin(parseId(message.getToId()), userId)) {
        return;
      }
    }
    throw new ApiException(HttpStatusCodes.FORBIDDEN, "Cannot delete others' message");
  }

  private MessageResult toResult(Message message, long requesterId) {
    return new MessageResult(message.getId(), message.getMsgId(), message.getConversationId(), message.getSeq(),
        message.getFromUserId(), message.getSenderUsername(), message.getToId(), message.getChatType(),
        message.getMsgType(), message.getContent(), message.getClientMsgId(), message.getReplyMsgId(),
        message.getAtUsers(), message.getStatus(), message.isEdited(), message.getEditedAt(), message.getCreatedBy(),
        message.getCreatedAt(), message.getUpdatedBy(), message.getUpdatedAt(),
        message.getMediaObjectIds().stream().map(objectId -> new MessageMedia(objectId,
            mediaReferences.accessUrl(requesterId, objectId, message.getMsgId()))).toList());
  }

  private List<String> normalizeMediaObjectIds(List<String> values, MsgType msgType) {
    List<String> objectIds = values == null ? List.of() : values.stream().filter(Objects::nonNull)
        .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    if (objectIds.size() > 9 || (mediaKind(msgType) == null && !objectIds.isEmpty())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Message media is invalid");
    }
    return objectIds;
  }

  private String mediaKind(MsgType msgType) {
    return switch (msgType) {
      case IMAGE -> "image";
      case VOICE -> "audio";
      case VIDEO -> "video";
      case FILE -> "attachment";
      default -> null;
    };
  }

}
