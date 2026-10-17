package io.openware.im.message.domain.message.readmodel;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgStatus;
import io.openware.common.enums.MsgType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息列表与分页查询的只读投影（standards/10 §领域模型与只读投影的边界，L266-279）。
 *
 * <p><b>为什么是投影而不是领域模型</b>：历史、按日期、上下文窗口、频道游标四条列表查询，
 * 以及搜索、管理端两个分页查询，都是只读聚合查询——结果不参与任何状态变更决策。
 * 规范要求用**只读投影类型**建模，放在 {@code domain.<ctx>.readmodel}；投影不定义业务行为、
 * 不被写路径当实体使用，排序（{@code created_at DESC} 等）留在适配器。
 *
 * <p><b>为什么这里可以带审计字段</b>：审计列不进**领域模型**（L299），但响应契约要回显
 * {@code createdBy/createdAt/updatedBy/updatedAt}。投影是查询结果类型而非聚合，天然承载这类展示字段——
 * 这正是 L266-279 把读路径与写路径分开建模的用意：不必为了让列表显示时间而把字段塞回聚合根。
 *
 * @param id              行标识
 * @param msgId           消息业务 id
 * @param conversationId  会话标识
 * @param seq             会话内序号
 * @param fromUserId      发送者 id
 * @param senderUsername  发送者用户名
 * @param toId            会话对端标识
 * @param chatType        会话类型
 * @param msgType         消息类型
 * @param content         正文
 * @param clientMsgId     客户端消息 id
 * @param replyMsgId      被回复消息 id
 * @param atUsers         @ 目标
 * @param mediaObjectIds  媒体对象 id
 * @param status          消息状态
 * @param edited          是否已编辑
 * @param editedAt        编辑时刻
 * @param createdBy       创建人（审计列）
 * @param createdAt       创建时间（审计列，也是发送时刻）
 * @param updatedBy       最后更新人（审计列）
 * @param updatedAt       最后更新时间（审计列）
 */
public record MessageView(Long id, String msgId, String conversationId, long seq, long fromUserId,
    String senderUsername, String toId, ChatType chatType, MsgType msgType, String content, String clientMsgId,
    String replyMsgId, List<String> atUsers, List<String> mediaObjectIds, MsgStatus status, boolean edited,
    LocalDateTime editedAt, Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
