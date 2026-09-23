package io.openware.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 管理端删除用户请求体，供 {@code AdminUserController} 使用。
 *
 * <p>刻意要求显式填写被删用户名：删除是不可逆操作，服务端会再次比对账号当前用户名，
 * 不一致一律 400 {@code USERNAME_CONFIRM_MISMATCH}（不依赖前端校验）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeleteAdminUserDto {
  @NotBlank(message = "请填写要删除的用户名")
  @Size(max = 64)
  private String confirmUsername;
}
