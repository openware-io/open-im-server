package io.openware.im.user.domain.cancellation.model;

import java.time.LocalDateTime;

/** 账号注销审计日志。 */
public class AccountCancellationLog {
  private Long id;
  private Long cancellationId;
  private Long userId;
  private AccountCancellationAction action;
  private String detail;
  private String operatorType;
  private Long operatorId;
  private String ip;
  private LocalDateTime occurredAt;

  public static AccountCancellationLog create(Long cancellationId, Long userId, AccountCancellationAction action,
      String detail, String operatorType, Long operatorId, String ip, LocalDateTime occurredAt) {
    AccountCancellationLog log = new AccountCancellationLog();
    log.cancellationId = cancellationId;
    log.userId = userId;
    log.action = action;
    log.detail = detail;
    log.operatorType = operatorType;
    log.operatorId = operatorId;
    log.ip = ip;
    log.occurredAt = occurredAt;
    return log;
  }

  public void restore(Long id, Long cancellationId, Long userId, AccountCancellationAction action, String detail,
      String operatorType, Long operatorId, String ip, LocalDateTime occurredAt) {
    this.id = id;
    this.cancellationId = cancellationId;
    this.userId = userId;
    this.action = action;
    this.detail = detail;
    this.operatorType = operatorType;
    this.operatorId = operatorId;
    this.ip = ip;
    this.occurredAt = occurredAt;
  }

  public Long getId() { return id; }
  public Long getCancellationId() { return cancellationId; }
  public Long getUserId() { return userId; }
  public AccountCancellationAction getAction() { return action; }
  public String getDetail() { return detail; }
  public String getOperatorType() { return operatorType; }
  public Long getOperatorId() { return operatorId; }
  public String getIp() { return ip; }
  public LocalDateTime getOccurredAt() { return occurredAt; }
}
