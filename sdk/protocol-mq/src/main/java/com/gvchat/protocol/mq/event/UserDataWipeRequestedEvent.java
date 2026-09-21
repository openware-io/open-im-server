package com.gvchat.protocol.mq.event;

import java.time.Instant;

/** 用户数据擦除请求事件：账号注销硬删完成后广播，供接入层通知各端 App 清理本地数据与缓存。 */
public record UserDataWipeRequestedEvent(
    String eventId,
    Long userId,
    Instant occurredAt) {
}
