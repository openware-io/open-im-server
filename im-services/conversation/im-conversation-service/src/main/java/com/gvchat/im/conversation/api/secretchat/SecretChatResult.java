package com.gvchat.im.conversation.api.secretchat;

import java.time.LocalDateTime;

public record SecretChatResult(Long id, Long userA, Long userB, Long peerUserId, String status, String safeCode,
    String destroyPolicy, String userAPublicKey, String userBPublicKey, String handshakeState,
    Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
