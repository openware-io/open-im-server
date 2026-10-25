package io.openware.platform.identity.api.controller;

import io.openware.platform.identity.application.AccountApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

  public record AccountInfo(Long id, String accountType, String status) {
  }
}
