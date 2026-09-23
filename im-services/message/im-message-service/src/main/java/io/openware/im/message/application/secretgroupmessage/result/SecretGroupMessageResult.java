package io.openware.im.message.application.secretgroupmessage.result;

import java.time.LocalDateTime;

public record SecretGroupMessageResult(Long id, Long secretGroupId, String msgId, Long fromUserId, Long recipientUserId,
    String ciphertext, Long seq, String status, LocalDateTime destroyAt, LocalDateTime createdAt) {
}
