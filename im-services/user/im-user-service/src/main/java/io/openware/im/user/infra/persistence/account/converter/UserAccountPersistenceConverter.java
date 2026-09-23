package io.openware.im.user.infra.persistence.account.converter;

import io.openware.im.user.domain.account.model.SelfDestructPolicy;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
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
        UserAccountRole.valueOf(po.getRole().toUpperCase()), po.getCreatedBy(), po.getCreatedAt(),
        po.getUpdatedBy(), po.getUpdatedAt());
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
    po.setCreatedBy(account.getCreatedBy());
    po.setCreatedAt(account.getCreatedAt());
    po.setUpdatedBy(account.getUpdatedBy());
    po.setUpdatedAt(account.getUpdatedAt());
    return po;
  }
}
