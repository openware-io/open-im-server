package io.openware.im.user.infra.persistence.account.converter;

import io.openware.im.user.domain.account.model.SelfDestructPolicy;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.readmodel.UserAccountView;
import io.openware.im.user.infra.persistence.account.po.UserAccountPo;

public final class UserAccountPersistenceConverter {
  private UserAccountPersistenceConverter() {
  }

  public static UserAccount toDomain(UserAccountPo po) {
    UserAccount account = new UserAccount();
    account.restore(
        po.getId(), po.getUsername(), po.getNickname(), po.getAvatar(), po.getPassword(), po.getEmail(),
        po.getPhone(), po.getSignature(), UserAccountStatus.valueOf(po.getStatus().toUpperCase()),
        po.getStatusVersion(),
        SelfDestructPolicy.fromValue(po.getSelfDestructPolicy()), po.getSelfDestructAt(), po.getLastLoginAt(),
        UserAccountRole.valueOf(po.getRole().toUpperCase()));
    return account;
  }

  public static UserAccountPo toPo(UserAccount account) {
    UserAccountPo po = new UserAccountPo();
    po.setId(account.getId());
    po.setUsername(account.getUsername());
    po.setNickname(account.getNickname());
    po.setAvatar(account.getAvatar());
    po.setPassword(account.getPasswordHash());
    po.setEmail(account.getEmail());
    po.setPhone(account.getPhone());
    po.setSignature(account.getSignature());
    po.setStatus(account.getStatus().name().toLowerCase());
    po.setStatusVersion(account.getStatusVersion());
    po.setSelfDestructPolicy(account.getSelfDestructPolicy().value());
    po.setSelfDestructAt(account.getSelfDestructAt());
    po.setLastLoginAt(account.getLastLoginAt());
    po.setRole(account.getRole().name().toLowerCase());
    // 记账列不来自领域模型：新增/更新由适配器按端口参数填写。
    return po;
  }

  /**
   * 管理端列表的只读投影（standards/10 L266-279）：列表不参与状态变更，用投影建模，
   * 响应需要的审计列随之由投影携带，而不是塞回领域模型。
   */
  public static UserAccountView toView(UserAccountPo po) {
    return new UserAccountView(po.getId(), po.getUsername(), po.getNickname(), po.getAvatar(), po.getEmail(),
        po.getPhone(), po.getSignature(), UserAccountStatus.valueOf(po.getStatus().toUpperCase()),
        po.getStatusVersion(), UserAccountRole.valueOf(po.getRole().toUpperCase()), po.getCreatedAt(),
        po.getUpdatedAt());
  }
}
