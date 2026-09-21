package com.gvchat.im.conversation.api.secretgroupchat;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSecretGroupChatRequest {
  /** 初始成员用户标识（不含群主，群主由当前登录用户充当）。 */
  @NotNull
  @NotEmpty
  private List<Long> memberUserIds;
}
