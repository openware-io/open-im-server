package com.gvchat.protocol.mq.event;

import java.time.Instant;
import java.util.List;

/**
 * 会话记录被清空事件（一方在服务端清空私聊/群聊后，通知其余受影响成员清理本地与服务端投影）。
 *
 * <p>语义：发起方调用 clear-private / clear-group 时，服务端已把该会话的消息实体硬删除；
 * 本事件用于驱动「对方的端缓存、中间件缓存与投影数据」一并清理，保证三层一致。
 */
public record ChatClearedEvent(
    String eventId,
    int eventVersion,
    Instant clearedAt,
    /** private | group | secret_private | secret_group */
    String chatType,
    String conversationId,
    /** 私聊为对端 userId；群聊为 groupId；密聊为 secretChatId；密群为 secretGroupId */
    String targetId,
    long clearedByUserId,
    /** 需要被通知清理的接收方用户（私聊=对端；群聊=除发起人外全部成员） */
    List<Long> recipientUserIds,
    /** 发起清空的用户所在端需要一并清理的会话标识（供客户端对齐） */
    String reason) {
}
