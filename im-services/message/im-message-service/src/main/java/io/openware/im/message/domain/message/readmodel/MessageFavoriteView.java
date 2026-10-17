package io.openware.im.message.domain.message.readmodel;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.time.LocalDateTime;

/**
 * 收藏列表的只读投影（standards/10 §领域模型与只读投影的边界，L266-279）。
 *
 * <p><b>为什么是投影而不是领域模型</b>：收藏列表是「列表查询」——结果不参与任何状态变更决策，
 * 规范要求用**只读投影类型**建模，放在 {@code domain.<ctx>.readmodel}。
 * 投影不定义业务行为、不被写路径当实体使用。
 *
 * <p><b>为什么这里可以带 {@code favoritedAt}</b>：审计列不进**领域模型**（L299），
 * 但列表要按收藏时刻倒序展示。投影是查询结果类型而非聚合，天然承载这类展示字段——
 * 这正是 L266-279 把读路径与写路径分开建模的用意：不必为了让列表显示时间而把字段塞回聚合根。
 *
 * <p>快照字段（{@code msgTypeSnapshot} 等）来自 V13 及之后的收藏行；更早的历史行全为 null，
 * 由应用层回落到权威消息或渲染占位。
 *
 * @param msgId                  被收藏的消息 id
 * @param peerId                 会话对端标识
 * @param chatType               会话类型
 * @param favoritedAt            收藏时刻（排序键）
 * @param msgTypeSnapshot        收藏时的消息类型快照
 * @param contentSnapshot        收藏时的正文快照
 * @param fromUserIdSnapshot     收藏时的发送者 id 快照
 * @param senderUsernameSnapshot 收藏时的发送者用户名快照
 * @param sentAtSnapshot         收藏时原消息的发送时刻快照
 */
public record MessageFavoriteView(String msgId, String peerId, ChatType chatType, LocalDateTime favoritedAt,
    MsgType msgTypeSnapshot, String contentSnapshot, Long fromUserIdSnapshot, String senderUsernameSnapshot,
    LocalDateTime sentAtSnapshot) {
}
