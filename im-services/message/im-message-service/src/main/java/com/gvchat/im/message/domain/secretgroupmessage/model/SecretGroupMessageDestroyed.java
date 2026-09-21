package com.gvchat.im.message.domain.secretgroupmessage.model;

import java.time.LocalDateTime;

/** 私密群聊消息销毁痕迹：msgId + 销毁时刻 + 原因（供端侧增量同步）。 */
public record SecretGroupMessageDestroyed(Long secretGroupId, String msgId, LocalDateTime destroyAt, String reason) {
}
