package io.openware.im.user.domain.cancellation.model;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 账号注销申请聚合：记录申请状态与删除步骤进度，由调度任务异步硬删账号与关联数据。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；它们在本服务里没有领域或应用层读者。
 * 操作人恒为申请人本人，适配器可由 {@code userId} 得出。
 *
 * <p><b>注意与业务时间字段的区别</b>：{@code requestedAt}/{@code processingAt}/{@code completedAt}
 * 是注销生命周期的事实，**保留在模型上**，因此 {@code markProcessing}/{@code complete}/{@code fail}
 * 的 {@code occurredAt} 参数也保留——它同时喂业务字段，不只为审计列服务。
 */
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
    return application;
  }

  public boolean isActive() {
    return status == AccountCancellationStatus.PENDING || status == AccountCancellationStatus.PROCESSING;
  }

  public void markProcessing(LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.PROCESSING;
    this.processingAt = occurredAt;
    this.rowVersion++;
  }

  public void complete(Set<AccountCancellationStep> steps, LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.COMPLETED;
    this.completedSteps = steps == null ? EnumSet.noneOf(AccountCancellationStep.class)
        : EnumSet.copyOf(steps);
    this.completedAt = occurredAt;
    this.failureReason = null;
    this.rowVersion++;
  }

  public void fail(String reason, LocalDateTime occurredAt) {
    this.status = AccountCancellationStatus.FAILED;
    this.failureReason = reason;
    this.rowVersion++;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long userId, String username, String nickname, String phone, String email,
      AccountCancellationStatus status, String statusTokenHash, String source,
      Set<AccountCancellationStep> completedSteps, String requestedIp, LocalDateTime requestedAt,
      LocalDateTime processingAt, LocalDateTime completedAt, String failureReason, long rowVersion) {
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
}
