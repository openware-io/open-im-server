package io.openware.platform.identity.api.controller;

import io.openware.platform.identity.application.AccountApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.openware.common.exception.ApiException;
import org.springframework.web.bind.annotation.RestController;

/** 内部账号查询：tenant 平台角色授予前校验 account_type/status。 */
@RestController
@RequestMapping("/internal/identity/accounts")
public class InternalAccountController {

  private final AccountApplicationService accounts;

  public InternalAccountController(AccountApplicationService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/{id}")
  public AccountInfo get(@PathVariable Long id) {
    AccountApplicationService.AccountView account = accounts.findAccountView(id);
    if (account == null) {
      return null;
    }
    return new AccountInfo(account.id(), account.accountType(), account.status());
  }

  /**
   * 内部：按登录标识查/建 SaaS 账号。供 platform-admin-service 开通运营人员或
   * 平台初始化租户管理员使用；不暴露给浏览器。
   */
  /** 内部：按手机号/登录标识查找 SaaS accountId，供后台代客下单关联客户。 */
  @GetMapping("/lookup")
  public Long lookup(@RequestParam String loginType, @RequestParam String loginIdentifier) {
    Long accountId = accounts.findAccountIdByLogin(loginType, loginIdentifier);
    if (accountId == null) {
      throw new ApiException(404, "ACCOUNT_NOT_FOUND", "账号不存在");
    }
    return accountId;
  }

  @PostMapping("/ensure")
  public AccountInfo ensure(@RequestBody EnsureAccountRequest req) {
    var account = accounts.ensureAccount(req.loginType(), req.loginIdentifier(), req.accountType());
    return new AccountInfo(account.id(), account.accountType(), account.status());
  }

  /** 内部：设置/重设 C 端登录密码（运营开通使用）。 */
  @PostMapping("/{accountId}/password")
  public void resetPassword(@PathVariable Long accountId, @RequestBody BindLoginIdentityRequest req) {
    accounts.resetPassword(accountId, req.loginType(), req.loginIdentifier(), req.credential());
  }

  /** 内部：为已有账号绑定可密码登录的标识。 */
  @PostMapping("/{accountId}/login-identity")
  public void bindLoginIdentity(@PathVariable Long accountId, @RequestBody BindLoginIdentityRequest req) {
    accounts.bindPasswordLogin(accountId, req.loginType(), req.loginIdentifier(), req.credential());
  }

  public record EnsureAccountRequest(String loginType, String loginIdentifier, String accountType) {}
  public record BindLoginIdentityRequest(String loginType, String loginIdentifier, String credential) {}

  public record AccountInfo(Long id, String accountType, String status) {
  }
}
