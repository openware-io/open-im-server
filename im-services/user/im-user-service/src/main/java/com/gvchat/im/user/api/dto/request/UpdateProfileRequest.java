package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {
  @Size(max = 128)
  private String nickname;
  @Size(max = 64)
  private String avatar;
  @Email
  private String email;
  @Size(max = 30)
  private String phone;
  @Size(max = 512)
  private String signature;
}
