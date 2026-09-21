package com.gvchat.im.message.application.secretmessage.command;

import java.util.List;

public record PostSecretMessageCommand(Long secretChatId, String msgId, String ciphertext,
    List<String> mediaObjectIds) {
}
