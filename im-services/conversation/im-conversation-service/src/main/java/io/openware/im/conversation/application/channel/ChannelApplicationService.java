package io.openware.im.conversation.application.channel;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.conversation.api.channel.ChannelResult;
import io.openware.im.conversation.api.channel.CreateChannelRequest;
import io.openware.im.conversation.domain.channel.model.Channel;
import io.openware.im.conversation.domain.channel.model.ChannelSubscription;
import io.openware.im.conversation.domain.channel.repository.ChannelRepository;
import io.openware.im.conversation.domain.channel.repository.ChannelSubscriptionRepository;
import io.openware.im.conversation.domain.channel.repository.Persisted;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 频道应用服务：承载创建/订阅/查询用例，业务规则（owner 发布权）下沉领域模型。
 *
 * <p>订阅为幂等语义：同一用户重复订阅直接返回（唯一约束兜底并发）。</p>
 *
 * <p><b>审计值从哪来</b>（standards/10 §聚合装配与持久化契约）：{@code Channel} 不带
 * {@code createdBy/createdAt/updatedBy/updatedAt}，端口以 {@link Persisted} 把「聚合 + 记账快照」一起返回，
 * {@link ChannelResult} 需要的四个值从 {@code audit()} 取。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelApplicationService {
  private final ChannelRepository channelRepository;
  private final ChannelSubscriptionRepository subscriptionRepository;

  @Transactional
  public ChannelResult createChannel(long userId, CreateChannelRequest request) {
    Persisted<Channel> saved = channelRepository.save(
        Channel.register(userId, request.getName(), request.getAvatar(), request.getAnnouncement()));
    log.info("Channel created, channelId={}, ownerId={}, name={}", saved.aggregate().getId(), userId,
        saved.aggregate().getName());
    return toResult(saved, "owner", false, 0);
  }

  @Transactional
  public ChannelResult subscribe(long userId, long channelId) {
    if (channelRepository.findById(channelId).isEmpty()) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Channel not found");
    }
    if (subscriptionRepository.findByChannelIdAndUserId(channelId, userId).isEmpty()) {
      subscriptionRepository.save(ChannelSubscription.subscribe(channelId, userId));
      log.info("Channel subscribed, channelId={}, userId={}", channelId, userId);
    }
    return getChannel(userId, channelId);
  }

  @Transactional(readOnly = true)
  public List<ChannelResult> listMyChannels(long userId) {
    List<Long> ids = subscriptionRepository.findByUserId(userId).stream()
        .map(ChannelSubscription::getChannelId).toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    return channelRepository.findByIds(ids).stream()
        .map(row -> toResult(row, row.aggregate().isOwnedBy(userId) ? "owner" : "subscriber", true,
            (int) subscriptionRepository.countByChannelId(row.aggregate().getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public ChannelResult getChannel(long userId, long channelId) {
    Persisted<Channel> channel = channelRepository.findById(channelId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Channel not found"));
    return toResultFor(userId, channel);
  }

  /** 通过频道号（分享码）查频道；用于「输入频道号订阅」。 */
  @Transactional(readOnly = true)
  public ChannelResult getChannelByCode(long userId, String code) {
    Persisted<Channel> channel = channelRepository.findByCode(code)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Channel not found"));
    return toResultFor(userId, channel);
  }

  /** 按名称搜索公开频道（返回最近创建的 active 频道，含当前用户的订阅状态）。 */
  @Transactional(readOnly = true)
  public List<ChannelResult> searchChannels(long userId, String keyword, int limit) {
    return channelRepository.searchByName(keyword, limit).stream()
        .map(row -> toResultFor(userId, row))
        .toList();
  }

  private ChannelResult toResultFor(long userId, Persisted<Channel> row) {
    long channelId = row.aggregate().getId();
    boolean subscribed = subscriptionRepository.findByChannelIdAndUserId(channelId, userId).isPresent();
    String myRole = row.aggregate().isOwnedBy(userId) ? "owner" : (subscribed ? "subscriber" : "none");
    return toResult(row, myRole, subscribed, (int) subscriptionRepository.countByChannelId(channelId));
  }

  @Transactional(readOnly = true)
  public boolean isOwner(long userId, long channelId) {
    return channelRepository.findById(channelId)
        .map(row -> row.aggregate().isOwnedBy(userId)).orElse(false);
  }

  @Transactional(readOnly = true)
  public boolean isSubscribed(long channelId, long userId) {
    return subscriptionRepository.findByChannelIdAndUserId(channelId, userId).isPresent();
  }

  /** 频道全部订阅者用户 id（消息通知 fan-out 用）。 */
  @Transactional(readOnly = true)
  public List<Long> listSubscriberUserIds(long channelId) {
    return subscriptionRepository.listSubscriberUserIds(channelId);
  }

  /** 取消订阅：幂等（未订阅也返回成功）。 */
  @Transactional
  public void unsubscribe(long userId, long channelId) {
    subscriptionRepository.deleteByChannelIdAndUserId(channelId, userId);
    log.info("Channel unsubscribed, channelId={}, userId={}", channelId, userId);
  }

  /** 更新频道信息（名称/头像/公告），仅 owner。 */
  @Transactional
  public ChannelResult updateChannel(long userId, long channelId, String name, String avatar,
      String announcement) {
    Channel channel = requireOwned(userId, channelId);
    Persisted<Channel> updated = channelRepository.save(channel.update(
        name == null || name.isBlank() ? channel.getName() : name.trim(),
        avatar,
        announcement));
    return toResultFor(userId, updated);
  }

  /** 删除频道（硬删除 + 清理订阅关系），仅 owner。 */
  @Transactional
  public void deleteChannel(long userId, long channelId) {
    requireOwned(userId, channelId);
    subscriptionRepository.deleteByChannelId(channelId);
    channelRepository.deleteById(channelId);
    log.info("Channel deleted, channelId={}, userId={}", channelId, userId);
  }

  private Channel requireOwned(long userId, long channelId) {
    Channel channel = channelRepository.findById(channelId)
        .map(Persisted::aggregate)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Channel not found"));
    if (!channel.isOwnedBy(userId)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only channel owner can manage channel");
    }
    return channel;
  }

  private ChannelResult toResult(Persisted<Channel> row, String myRole, boolean subscribed, int memberCount) {
    Channel channel = row.aggregate();
    return new ChannelResult(channel.getId(), channel.getCode(), channel.getOwnerId(), channel.getName(),
        channel.getAvatar(), channel.getAnnouncement(), channel.getDiscussionGroupId(), channel.getStatus(),
        myRole, subscribed, memberCount, row.audit().createdAt(), row.audit().updatedAt());
  }
}
