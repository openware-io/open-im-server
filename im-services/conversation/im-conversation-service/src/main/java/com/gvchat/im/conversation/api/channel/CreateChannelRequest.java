package com.gvchat.im.conversation.api.channel;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateChannelRequest {
  @NotBlank
  private String name;
  private String avatar;
  private String announcement;
}
