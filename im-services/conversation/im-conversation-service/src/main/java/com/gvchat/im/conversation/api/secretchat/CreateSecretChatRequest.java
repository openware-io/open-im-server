package com.gvchat.im.conversation.api.secretchat;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSecretChatRequest {
  @NotNull
  private Long userB;

  /** 发起方本端设备公钥（可选，用于创建时即完成握手，实现「无需对方接受即可发送」）。 */
  private String publicKey;
}
