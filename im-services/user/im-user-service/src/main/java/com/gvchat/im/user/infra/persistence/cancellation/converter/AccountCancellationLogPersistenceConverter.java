package com.gvchat.im.user.infra.persistence.cancellation.converter;

import com.gvchat.im.user.domain.cancellation.model.AccountCancellationAction;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationLog;
import com.gvchat.im.user.infra.persistence.cancellation.po.AccountCancellationLogPo;

/** 账号注销审计日志 PO 与领域模型互转。 */
public final class AccountCancellationLogPersistenceConverter {
  private AccountCancellationLogPersistenceConverter() {
  }

  public static AccountCancellationLog toDomain(AccountCancellationLogPo po) {
    AccountCancellationLog log = new AccountCancellationLog();
    log.restore(po.getId(), po.getCancellationId(), po.getUserId(), AccountCancellationAction.fromValue(po.getAction()),
        po.getDetail(), po.getOperatorType(), po.getOperatorId(), po.getIp(), po.getOccurredAt());
    return log;
  }

  public static AccountCancellationLogPo toPo(AccountCancellationLog log) {
    AccountCancellationLogPo po = new AccountCancellationLogPo();
    po.setCancellationId(log.getCancellationId());
    po.setUserId(log.getUserId());
    po.setAction(log.getAction() == null ? null : log.getAction().name().toLowerCase());
    po.setDetail(log.getDetail());
    po.setOperatorType(log.getOperatorType());
    po.setOperatorId(log.getOperatorId());
    po.setIp(log.getIp());
    po.setOccurredAt(log.getOccurredAt());
    return po;
  }
}
