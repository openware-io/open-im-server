package io.openware.im.user.domain.account.event;

import java.time.Instant;

/** 用户聊天记录清理事件：账号自毁策略到期后广播，供消息/会话服务清理该用户聊天记录（账号保留）。 */
public record UserChatRecordsPurged(String eventId, Long userId, Instant occurredAt) {
}
