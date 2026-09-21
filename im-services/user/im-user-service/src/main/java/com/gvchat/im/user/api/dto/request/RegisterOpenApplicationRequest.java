package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 开放平台应用注册请求：脚手架注册即 APPROVED，appSecret 仅创建时返回一次。 */
@Getter
@Setter
public class RegisterOpenApplicationRequest {
  @NotBlank
  private String appName;
  private String subjectName;
  private String appType;
  @NotBlank
  private String callbackUrl;
  @NotEmpty
  private List<String> scopes;
}
