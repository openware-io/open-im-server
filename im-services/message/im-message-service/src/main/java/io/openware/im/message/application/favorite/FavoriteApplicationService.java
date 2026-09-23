package io.openware.im.message.application.favorite;

import io.openware.common.constant.AppConstants;
import io.openware.common.enums.ChatType;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.message.application.favorite.command.AddFavoriteCommand;
import io.openware.im.message.application.favorite.command.AddFavoritesBatchCommand;
import io.openware.im.message.application.favorite.query.FavoriteListQuery;
import io.openware.im.message.application.favorite.result.FavoriteBatchResult;
import io.openware.im.message.application.favorite.result.FavoritePageResult;
import io.openware.im.message.application.favorite.result.FavoriteResult;
import io.openware.im.message.application.favorite.result.FavoriteSourceResult;
import io.openware.im.message.application.favorite.result.FavoriteSourceState;
import io.openware.im.message.domain.message.model.Message;
import io.openware.im.message.domain.message.model.MessageFavorite;
import io.openware.im.message.domain.message.port.ChannelMembershipPort;
import io.openware.im.message.domain.message.port.FriendRelationPort;
import io.openware.im.message.domain.message.port.GroupMembershipPort;
import io.openware.im.message.domain.message.repository.MessageFavoriteRepository;
import io.openware.im.message.domain.message.repository.MessageRepository;
import io.openware.im.message.domain.secretgroupmessage.port.SecretGroupChatPort;
import io.openware.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import io.openware.im.message.domain.secretmessage.port.SecretChatParticipantPort;
import io.openware.im.message.domain.secretmessage.repository.SecretMessageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息收藏应用服务：收藏落 msgId + 会话定位信息 + 「收藏时」内容快照，列表时优先回查权威消息。
 *
 * <p>收藏/取消收藏均幂等：重复收藏命中 (user_id, msg_id) 唯一约束不重复插入；
 * 取消不存在的收藏静默成功。</p>
 *
 * <p>「收藏内容永远可读」：收藏成功时把权威消息的类型/正文/发送者/发送时间写入快照列；读取时
 * 实时消息优先（保证能看到编辑后的最新内容），实时消息已不存在或已无权访问时回落到快照。
 * 快照列（V13）之前的历史行快照为空，行为与改动前完全一致（占位渲染）。</p>
 *
 * <p>安全（IDOR 修复）：收藏落库前校验消息存在且当前用户有权访问（按消息真实 chatType/toId
 * 走成员/好友/订阅鉴权）；列表回查后对已无权访问且无快照的收藏返回占位，不泄露正文。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteApplicationService {
  private final MessageFavoriteRepository favoriteRepository;
  private final MessageRepository messageRepository;
  private final FriendRelationPort friendRelationPort;
  private final GroupMembershipPort groupMembershipPort;
  private final ChannelMembershipPort channelMembershipPort;
  private final SecretMessageRepository secretMessageRepository;
  private final SecretChatParticipantPort secretChatParticipantPort;
  private final SecretGroupMessageRepository secretGroupMessageRepository;
  private final SecretGroupChatPort secretGroupChatPort;

  @Transactional
  public void favorite(AddFavoriteCommand command) {
    if (favoriteOne(command)) {
      log.info("Message favorited, userId={}, msgId={}, chatType={}", command.userId(), command.msgId(),
          command.chatType().getValue());
    }
  }

  /**
   * 批量收藏：逐条复用单条收藏的授权与落库逻辑，单条失败只记为该条 created=false，绝不整批失败。
   *
   * <p>刻意不加 {@code @Transactional}：整批共用一个事务会让某一条的失败/异常牵连其余条目；
   * 这里每条各自提交，保证「一条坏数据不回滚整批」。</p>
   */
  public FavoriteBatchResult favoriteBatch(AddFavoritesBatchCommand command) {
    List<FavoriteBatchResult.Item> items = new ArrayList<>(command.messageIds().size());
    int created = 0;
    int skipped = 0;
    for (String msgId : command.messageIds()) {
      boolean inserted = false;
      try {
        inserted = favoriteOne(new AddFavoriteCommand(command.userId(), msgId, command.peerId(),
            command.chatType()));
      } catch (Exception ex) {
        // 单条消息不存在/无权访问/下游抖动：记为未创建并继续，不影响其余条目。
        log.warn("Batch favorite item skipped, userId={}, peerId={}, msgId={}, chatType={}", command.userId(),
            command.peerId(), msgId, command.chatType().getValue(), ex);
      }
      items.add(new FavoriteBatchResult.Item(msgId, inserted));
      if (inserted) {
        created++;
      } else {
        skipped++;
      }
    }
    log.info("Messages batch favorited, userId={}, chatType={}, created={}, skipped={}", command.userId(),
        command.chatType().getValue(), created, skipped);
    return new FavoriteBatchResult(created, skipped, items);
  }

  /**
   * 单条收藏落库：先按单条授权逻辑校验（与 {@link #authorize} 共用，不另写一份），
   * 再把授权通过的权威消息写入收藏时快照。
   *
   * @return true 表示本次新建收藏；false 表示幂等命中已存在的收藏。
   */
  private boolean favoriteOne(AddFavoriteCommand command) {
    Message message = authorize(command);
    LocalDateTime now = utcNow();
    MessageFavorite favorite = message == null
        // 密聊等不走 msg_message 权威通道的会话：不落快照（服务端只存密文且设计为定时销毁）。
        ? MessageFavorite.create(command.userId(), command.msgId(), command.peerId(), command.chatType(), now)
        : MessageFavorite.create(command.userId(), command.msgId(), command.peerId(), command.chatType(), now,
            message.getMsgType(), message.getContent(), message.getFromUserId(), message.getSenderUsername(),
            message.getCreatedAt());
    return favoriteRepository.saveIfAbsent(favorite);
  }

  /**
   * 收藏授权：校验消息存在且当前用户有权访问。
   *
   * @return 授权通过的权威消息；密聊等非 {@code msg_message} 通道返回 null（无正文快照可写）。
   */
  private Message authorize(AddFavoriteCommand command) {
    if (command.chatType() == ChatType.SECRET) {
      authorizeSecretChat(command);
      return null;
    }
    if (command.chatType() == ChatType.SECRET_GROUP) {
      authorizeSecretGroup(command);
      return null;
    }
    Message message = messageRepository.findByMsgId(command.msgId()).orElse(null);
    if (message == null) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Message not found");
    }
    if (!canAccess(command.userId(), message)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not allowed to favorite this message");
    }
    return message;
  }

  private void authorizeSecretChat(AddFavoriteCommand command) {
    long secretChatId = parseId(command.peerId());
    if (secretMessageRepository.findByMsgId(secretChatId, command.msgId()).isEmpty()) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Message not found");
    }
    if (!secretChatParticipantPort.findParticipants(secretChatId).contains(command.userId())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not allowed to favorite this message");
    }
  }

  private void authorizeSecretGroup(AddFavoriteCommand command) {
    long secretGroupId = parseId(command.peerId());
    if (secretGroupMessageRepository.findByMsgId(secretGroupId, command.msgId()).isEmpty()) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Message not found");
    }
    if (!secretGroupChatPort.findParticipants(secretGroupId).contains(command.userId())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Not allowed to favorite this message");
    }
  }

  @Transactional
  public void unfavorite(long userId, String msgId) {
    favoriteRepository.deleteByUserIdAndMsgId(userId, msgId);
    log.info("Message unfavorited, userId={}, msgId={}", userId, msgId);
  }

  @Transactional(readOnly = true)
  public FavoritePageResult listFavorites(FavoriteListQuery query) {
    int page = page(query.page());
    int pageSize = pageSize(query.pageSize());
    MessageFavoriteRepository.FavoritePage favorites = favoriteRepository.findByUserId(query.userId(), page, pageSize);
    List<String> msgIds = favorites.items().stream().map(MessageFavorite::getMsgId).distinct().toList();
    Map<String, Message> byMsgId = messageRepository.findByMsgIds(msgIds).stream()
        .collect(Collectors.toMap(Message::getMsgId, Function.identity()));
    List<FavoriteResult> items = favorites.items().stream()
        .map(favorite -> toResult(query.userId(), favorite, byMsgId.get(favorite.getMsgId())))
        .toList();
    return new FavoritePageResult(items, favorites.total(), page, pageSize);
  }

  private FavoriteResult toResult(long userId, MessageFavorite favorite, Message message) {
    if (message != null && canAccess(userId, message)) {
      // 实时消息优先：编辑后的内容、撤回/删除前的权威状态以消息表为准。
      return new FavoriteResult(favorite.getMsgId(), favorite.getPeerId(), favorite.getChatType(),
          message.getMsgType(), message.getContent(), message.getFromUserId(), message.getSenderUsername(),
          message.getCreatedAt(), favorite.getCreatedAt());
    }
    if (favorite.hasSnapshot()) {
      // 权威消息已撤回/删除，或当前用户已无权访问：回落收藏时快照（收藏内容永远可读）。
      return new FavoriteResult(favorite.getMsgId(), favorite.getPeerId(), favorite.getChatType(),
          favorite.getMsgTypeSnapshot(), favorite.getContentSnapshot(), favorite.getFromUserIdSnapshot(),
          favorite.getSenderUsernameSnapshot(), favorite.getSentAtSnapshot(), favorite.getCreatedAt());
    }
    // 快照列（V13）之前的历史行：行为与改动前完全一致——仅保留收藏定位信息，展示层渲染占位。
    return FavoriteResult.placeholder(favorite.getMsgId(), favorite.getPeerId(), favorite.getChatType(),
        favorite.getCreatedAt());
  }

  /**
   * 查询收藏对应的原消息是否仍可跳转。
   *
   * <p>刻意不加 {@code @Transactional}：查询本身失败必须 fail closed 返回
   * {@link FavoriteSourceState#LOOKUP_UNAVAILABLE}，不能因事务边界把异常再次抛出变成 500；
   * 且 {@link #canAccess} 会调用外部端口，不应被长事务包住。</p>
   */
  public FavoriteSourceResult locateSource(long userId, String msgId) {
    try {
      Message message = messageRepository.findByMsgId(msgId).orElse(null);
      if (message == null) {
        // 权威消息已硬删除（撤回/删除）：无源可回。
        return FavoriteSourceResult.of(FavoriteSourceState.MESSAGE_DELETED);
      }
      if (canAccess(userId, message)) {
        return FavoriteSourceResult.available(message.getConversationId(), message.getMsgId());
      }
      return FavoriteSourceResult.of(denialState(userId, message));
    } catch (Exception ex) {
      // 查询本身失败：绝不猜测可跳转。
      log.warn("Favorite source lookup failed, userId={}, msgId={}", userId, msgId, ex);
      return FavoriteSourceResult.of(FavoriteSourceState.LOOKUP_UNAVAILABLE);
    }
  }

  /**
   * 消息存在但当前用户不可访问时的归因：区分「会话/对端已不可用」与「访问被明确拒绝」。
   *
   * <p>只做归因分类，不做二次鉴权——「能不能访问」完全由 {@link #canAccess} 决定，
   * 这里不再调用任何成员/好友端口。</p>
   */
  private FavoriteSourceState denialState(long userId, Message message) {
    if (message.getChatType() == null || message.getToId() == null) {
      // 归属信息缺失，无法判定会话：明确拒绝。
      return FavoriteSourceState.NO_PERMISSION;
    }
    if (message.getChatType() == ChatType.PRIVATE) {
      boolean participant = message.getFromUserId() == userId || message.getToId().equals(String.valueOf(userId));
      // 非会话双方的私聊：明确拒绝；曾是会话双方但好友关系已解除：会话不可用。
      return participant ? FavoriteSourceState.CONVERSATION_UNAVAILABLE : FavoriteSourceState.NO_PERMISSION;
    }
    if (message.getChatType() == ChatType.GROUP || message.getChatType() == ChatType.CHANNEL) {
      // 群/频道已解散，或当前用户已被移出/退订：会话不可用。
      return FavoriteSourceState.CONVERSATION_UNAVAILABLE;
    }
    // SECRET / SECRET_GROUP 等不走 msg_message 权威通道：明确拒绝，不猜测可跳转。
    return FavoriteSourceState.NO_PERMISSION;
  }

  /**
   * 按消息真实 chatType/toId 校验当前用户可见性（与 {@code MessageApplicationService} 的
   * assertCanAccessHistory / canSynchronize 等价）：私聊需互为好友、群聊需成员、频道需订阅或所有者。
   * 单条收藏与批量收藏、原消息定位共用这一份授权逻辑。
   */
  private boolean canAccess(long userId, Message message) {
    if (message.getChatType() == null || message.getToId() == null) {
      return false;
    }
    try {
      if (message.getChatType() == ChatType.PRIVATE) {
        return canAccessPrivate(userId, message);
      }
      if (message.getChatType() == ChatType.CHANNEL) {
        long channelId = parseId(message.getToId());
        return channelMembershipPort.isOwner(channelId, userId)
            || channelMembershipPort.isSubscribed(channelId, userId);
      }
      if (message.getChatType() == ChatType.GROUP) {
        return groupMembershipPort.isMember(parseId(message.getToId()), userId);
      }
      // SECRET 等非普通会话消息不走 MessageRepository 权威通道，一律不可见。
      return false;
    } catch (Exception ex) {
      // 外部端口（好友/频道/群成员）调用失败时按「不可见」处理，避免单条坏数据/下游抖动导致整页 500。
      log.warn("Favorite access check failed, userId={}, msgId={}, chatType={}", userId, message.getMsgId(),
          message.getChatType(), ex);
      return false;
    }
  }

  private boolean canAccessPrivate(long userId, Message message) {
    if (message.getFromUserId() == userId) {
      // 我发送的私聊：对端为 toId。
      return friendRelationPort.areFriends(userId, parseId(message.getToId()));
    }
    // 我接收的私聊：toId 即我的 userId，对端为 fromUserId；非会话双方一律拒绝。
    return parseId(message.getToId()) == userId
        && friendRelationPort.areFriends(userId, message.getFromUserId());
  }

  private long parseId(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Invalid identifier");
    }
  }

  private LocalDateTime utcNow() {
    return LocalDateTime.now(Clock.systemUTC());
  }

  private int page(Integer page) {
    return page != null ? page : 1;
  }

  private int pageSize(Integer pageSize) {
    return Math.min(pageSize != null ? pageSize : AppConstants.DEFAULT_PAGE_SIZE, AppConstants.MAX_PAGE_SIZE);
  }
}
