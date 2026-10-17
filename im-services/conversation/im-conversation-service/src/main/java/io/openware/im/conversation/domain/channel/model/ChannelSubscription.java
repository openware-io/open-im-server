package io.openware.im.conversation.domain.channel.model;

/**
 * 频道订阅实体：用户与频道的订阅关系（幂等去重由唯一约束与领域行为保障）。
 *
 * <p><b>为什么不带审计列</b>（standards/10 §聚合装配与持久化契约）：订阅的 {@code created_at} 同时被
 * {@code channel_subscription.joined_at} 复用（加入时刻），两者都由适配器在写库时确定；响应需要的值经
 * {@code ChannelSubscriptionRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 * 因此 {@code subscribe} 也**不再接收 {@code operatorId}/{@code occurredAt}**。
 */
public class ChannelSubscription {
  private final Long id;
  private final Long channelId;
  private final Long userId;
  private final String notifySetting;

  private ChannelSubscription(Long id, Long channelId, Long userId, String notifySetting) {
    this.id = id;
    this.channelId = channelId;
    this.userId = userId;
    this.notifySetting = notifySetting;
  }

  public static ChannelSubscription subscribe(long channelId, long userId) {
    return new ChannelSubscription(null, channelId, userId, "default");
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static ChannelSubscription restore(Long id, Long channelId, Long userId, String notifySetting) {
    return new ChannelSubscription(id, channelId, userId, notifySetting);
  }

  public Long getId() { return id; }
  public Long getChannelId() { return channelId; }
  public Long getUserId() { return userId; }
  public String getNotifySetting() { return notifySetting; }
}
