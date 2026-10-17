package io.openware.im.user.domain.social.readmodel;

import io.openware.im.user.domain.social.model.FriendStatus;
import java.time.LocalDateTime;

/**
 * 管理端好友关系列表的只读投影（standards/10 L266-279）。
 *
 * <p>列表是只读聚合查询、结果不参与状态变更决策，因此用投影而非领域模型。
 *
 * @param id        关系行标识
 * @param userId    关系归属用户
 * @param friendId  好友用户
 * @param remark    备注
 * @param groupName 分组
 * @param status    关系状态
 * @param createdAt 创建时刻（排序键）
 */
public record FriendRelationView(Long id, Long userId, Long friendId, String remark, String groupName,
    FriendStatus status, LocalDateTime createdAt) {
}