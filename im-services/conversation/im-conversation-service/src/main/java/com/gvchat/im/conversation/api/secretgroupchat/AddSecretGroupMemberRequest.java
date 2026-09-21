package com.gvchat.im.conversation.api.secretgroupchat;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddSecretGroupMemberRequest {
  @NotNull
  private Long userId;
}
