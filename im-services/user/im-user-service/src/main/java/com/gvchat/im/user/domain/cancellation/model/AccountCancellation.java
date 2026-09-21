package com.gvchat.im.user.domain.cancellation.model;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/** 账号注销申请聚合：记录申请状态与删除步骤进度，由调度任务异步硬删账号与关联数据。 */
public class AccountCancellation {
  private Long id;
  private Long userId;
  private String username;
  private String nickname;
  private String phone;
  private String email;
  private AccountCancellationStatus status;
  private String statusTokenHash;
  private String source;
  private Set<AccountCancellationStep> completedSteps;
  private String requestedIp;
  private LocalDateTime requestedAt;
  private LocalDateTime processingAt;
  private LocalDateTime completedAt;
  private String failureReason;
  private long rowVersion;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public static AccountCancellation request(Long userId, String username, String nickname, String phone, String email,
      String statusTokenHash, String source, String requestedIp, LocalDateTime requestedAt) {
    AccountCancellation application = new AccountCancellation();
    application.userId = userId;
    application.username = username;
    application.nickname = nickname;
    application.phone = phone;
    application.email = email;
    application.status = AccountCancellationStatus.PENDING;
    application.statusTokenHash = statusTokenHash;
    application.source = source;
    application.completedSteps = EnumSet.noneOf(AccountCancellationStep.class);
    application.requestedIp = requestedIp;
    application.requestedAt = requestedAt;
    application.rowVersion = 1L;
    application.createdBy = userId;
    application.createdAt = requestedAt;
    application.updatedBy = userId;
    application.updatedAt = requestedAt;
    return application;
  }

  public boolean isActive() {
    return status == AccountCancellationStatus.PENDING || status == AccountCancellationStatus.PROCESSING;
  }

  public void markProcessing(LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.PROCESSING;
    this.processingAt = occurredAt;
    this.updatedAt = occurredAt;
    this.rowVersion++;
  }

  public void complete(Set<AccountCancellationStep> steps, LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.COMPLETED;
    this.completedSteps = steps == null ? EnumSet.noneOf(AccountCancellationStep.class)
        : EnumSet.copyOf(steps);
    this.completedAt = occurredAt;
    this.failureReason = null;
    this.updatedAt = occurredAt;
    this.rowVersion++;
  }

  public void fail(String reason, LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.FAILED;
    this.failureReason = reason;
    this.updatedAt = occurredAt;
    this.rowVersion++;
  }

  public void restore(Long id, Long userId, String username, String nickname, String phone, String email,
      AccountCancellationStatus status, String statusTokenHash, String source, Set<AccountCancellationStep> completedSteps,
      String requestedIp, LocalDateTime requestedAt, LocalDateTime processingAt, LocalDateTime completedAt,
      String failureReason, long rowVersion, Long createdBy, LocalDateTime createdAt, Long updatedBy,
      LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.username = username;
    this.nickname = nickname;
    this.phone = phone;
    this.email = email;
    this.status = status;
    this.statusTokenHash = statusTokenHash;
    this.source = source;
    this.completedSteps = completedSteps == null ? EnumSet.noneOf(AccountCancellationStep.class) : completedSteps;
    this.requestedIp = requestedIp;
    this.requestedAt = requestedAt;
    this.processingAt = processingAt;
    this.completedAt = completedAt;
    this.failureReason = failureReason;
    this.rowVersion = rowVersion;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getUsername() { return username; }
  public String getNickname() { return nickname; }
  public String getPhone() { return phone; }
  public String getEmail() { return email; }
  public AccountCancellationStatus getStatus() { return status; }
  public String getStatusTokenHash() { return statusTokenHash; }
  public String getSource() { return source; }
  public Set<AccountCancellationStep> getCompletedSteps() { return completedSteps; }
  public String getRequestedIp() { return requestedIp; }
  public LocalDateTime getRequestedAt() { return requestedAt; }
  public LocalDateTime getProcessingAt() { return processingAt; }
  public LocalDateTime getCompletedAt() { return completedAt; }
  public String getFailureReason() { return failureReason; }
  public long getRowVersion() { return rowVersion; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
