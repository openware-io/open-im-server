package io.openware.im.message.application.favorite.result;

/**
 * 收藏对应原消息的可用性状态，与客户端 {@code FavoriteSourceState} 的 wire 值一一对应。
 *
 * <p>只有 {@link #AVAILABLE} 允许客户端跳转到原会话/原消息；其余状态一律留在收藏详情页。</p>
 */
public enum FavoriteSourceState {
  /** 原消息仍存在且当前用户仍可访问，此时才返回可跳转的会话/消息 id。 */
  AVAILABLE,
  /** 权威消息已硬删除（撤回/删除），无源可回。 */
  MESSAGE_DELETED,
  /** 消息仍存在，但会话/对端已不可用（私聊不再互为好友、群/频道已解散或用户已被移出/退订）。 */
  CONVERSATION_UNAVAILABLE,
  /** 访问被明确拒绝（非会话双方、归属信息缺失、或不走权威消息通道的会话类型）。 */
  NO_PERMISSION,
  /** 查询本身失败：fail closed，绝不猜测可跳转。 */
  LOOKUP_UNAVAILABLE;
}
