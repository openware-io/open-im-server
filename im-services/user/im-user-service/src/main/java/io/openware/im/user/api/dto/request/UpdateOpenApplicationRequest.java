package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 开放平台应用更新请求：仅允许变更 callbackUrl 与 scopes，appId/appSecret/status 不可经此接口变更。 */
@Getter
@Setter
public class UpdateOpenApplicationRequest {
  @NotBlank
  private String callbackUrl;
  @NotEmpty
  private List<String> scopes;
}
