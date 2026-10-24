package io.openware.platform.identity.api.controller;

import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部账号查询：tenant 平台角色授予前校验 account_type/status。 */
@RestController
@RequestMapping("/internal/identity/accounts")
public class InternalAccountController {

  private final IdentityAccountRepository accounts;

  public InternalAccountController(IdentityAccountRepository accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/{id}")
  public AccountInfo get(@PathVariable Long id) {
    IdentityAccount account = accounts.findById(id).orElse(null);
    if (account == null) {
      return null;
    }
    return new AccountInfo(account.id(), account.accountType(), account.status());
  }

  public record AccountInfo(Long id, String accountType, String status) {
  }
}
