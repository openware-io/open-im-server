package io.openware.im.admin.controller;

import io.openware.common.dto.AdminListUsersDto;
import io.openware.common.dto.AdminUpdateUserStatusDto;
import io.openware.common.dto.DeleteAdminUserDto;
import io.openware.common.dto.PageResult;
import io.openware.im.admin.application.command.AdminUserDeletionApplicationService;
import io.openware.im.admin.application.query.AdminUserApplicationService;
import io.openware.im.user.api.admin.AdminUserDeleteResponse;
import io.openware.im.user.api.admin.AdminUserResponse;
import io.openware.im.user.api.admin.ChangeUserStatusResponse;
import io.openware.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@Tag(name = "用户管理")
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
  private final AdminUserApplicationService adminUserService;
  private final AdminUserDeletionApplicationService adminUserDeletionApplicationService;

/**
 */
  @GetMapping
  public PageResult<AdminUserResponse> list(@ModelAttribute AdminListUsersDto dto) {
    return adminUserService.listUsers(dto);
  }

/**
 */
  @GetMapping("/{id}")
  public AdminUserResponse detail(@PathVariable Long id) {
    return adminUserService.getUserDetail(id);
  }

/**
 */
  @PutMapping("/{id}/status")
  public ChangeUserStatusResponse updateStatus(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody AdminUpdateUserStatusDto dto) {
    return adminUserService.updateUserStatus(id, user.getId(), dto);
  }

  /**
   * 删除用户（硬删 + 级联清理，消息本体保留）。
   *
   * <p>IM 侧口径：必须带 {@code confirmUsername}；不能删除自己（409 {@code CANNOT_DELETE_SELF}）；
   * 管理员账号（{@code user.role='admin'}）409 {@code ADMIN_ACCOUNT_UNDELETABLE}；
   * 用户名不一致 400 {@code USERNAME_CONFIRM_MISMATCH}；账号不存在 404 {@code USER_NOT_FOUND}。
   * 不引入任何 SaaS/外部域门禁。
   */
  @DeleteMapping("/{id}")
  public AdminUserDeleteResponse delete(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody DeleteAdminUserDto dto) {
    return adminUserDeletionApplicationService.deleteUser(id, user.getId(), user.getUsername(), dto);
  }
}

