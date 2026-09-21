package com.gvchat.im.conversation.api.secretchat;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitHandshakeRequest {
  @NotBlank
  private String publicKey;
}
