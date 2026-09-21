package com.gvchat.im.message.application.secretgroupmessage.command;

import java.util.List;

public record PostSecretGroupMessageCommand(Long secretGroupId, String msgId, List<RecipientCiphertext> recipients,
    List<String> mediaObjectIds, List<Long> atUserIds) {
  public record RecipientCiphertext(Long userId, String ciphertext) {
  }
}
