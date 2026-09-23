package io.openware.im.conversation.api.secretchat;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetDestroyPolicyRequest {
  @NotBlank
  private String policy;
}
