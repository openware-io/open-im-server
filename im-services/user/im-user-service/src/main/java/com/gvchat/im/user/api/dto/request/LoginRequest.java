package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
  @NotBlank
  private String username;
  @NotBlank
  private String password;

  /** 设备唯一标识（多端登录会话记录用，可选）。 */
  @Size(max = 128)
  private String deviceId;
  /** 设备类型：mobile/desktop/tablet/web（可选）。 */
  @Size(max = 32)
  private String deviceType;
  /** 设备名称（可选）。 */
  @Size(max = 128)
  private String deviceName;
}
