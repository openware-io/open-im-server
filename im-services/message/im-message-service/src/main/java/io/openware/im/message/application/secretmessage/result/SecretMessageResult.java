package io.openware.im.message.application.secretmessage.result;

import java.time.LocalDateTime;

public record SecretMessageResult(Long id, Long secretChatId, String msgId, Long fromUserId, String ciphertext,
    Long seq, String status, LocalDateTime destroyAt, LocalDateTime createdAt) {
}
