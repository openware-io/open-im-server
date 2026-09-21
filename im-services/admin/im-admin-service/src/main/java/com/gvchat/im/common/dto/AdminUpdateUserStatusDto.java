package com.gvchat.common.dto;

import com.gvchat.common.enums.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 管理端更新用户状态请求体，供 {@code AdminUserController} 使用*/
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUpdateUserStatusDto {
  @NotNull
  private UserStatus status;
  @Min(1)
  private long expectedStatusVersion;
  @NotBlank
  @Size(max = 128)
  private String idempotencyKey;
  @NotBlank
  @Size(max = 512)
  private String reason;
  @NotBlank
  @Size(max = 128)
  private String correlationId;
}
