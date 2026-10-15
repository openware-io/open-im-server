package io.openware.platform.identity.api.converter;

import io.openware.platform.identity.api.dto.AccountView;
import io.openware.platform.identity.domain.account.model.IdentityAccount;

/**
 * 账号领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换职责收在这里，而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约
 * 反向绑到领域对象上，正是本次治理要消除的耦合。
 */
public final class AccountConverter {

  private AccountConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空，由控制器决定响应语义。 */
  public static AccountView toView(IdentityAccount account) {
    if (account == null) {
      return null;
    }
    return new AccountView(account.id(), account.status(), account.accountType());
  }
}
