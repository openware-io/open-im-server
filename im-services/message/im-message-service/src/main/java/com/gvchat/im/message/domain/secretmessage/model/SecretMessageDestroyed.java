package com.gvchat.im.message.domain.secretmessage.model;

import java.time.LocalDateTime;

/**
 * 私密消息销毁痕迹：仅 msgId + 销毁时刻 + 原因，**不含密文/内容/发送者**。
 *
 * <p>撤回、删除、定时销毁统一在此登记，供离线端增量同步（撤回→墓碑、其余→移除）；
 * 密文本体已在 {@code msg_secret_message} 硬删除，服务端不保留消息内容。</p>
 */
public record SecretMessageDestroyed(long secretChatId, String msgId, LocalDateTime destroyAt, String reason) {
}
