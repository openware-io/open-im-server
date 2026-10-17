package io.openware.im.user.domain.account.model;

import java.time.LocalDateTime;

/**
 * 用户账号聚合根。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息而非业务事实，由写路径在落库时确定
 * （适配器按端口参数写入）。响应需要的审计列经 {@code UserAccountRepository.Persisted} 的
 * {@code AuditSnapshot} 交给上层，而不是留在领域模型上。
 */
public class UserAccount {
  private Long id;
  private String username;
  private String nickname;
  private String avatar;
  private String passwordHash;
  private String email;
  private String phone;
  private String signature;
  private UserAccountStatus status;
  private long statusVersion;
  private SelfDestructPolicy selfDestructPolicy;
  private LocalDateTime selfDestructAt;
  private LocalDateTime lastLoginAt;
  private UserAccountRole role;

  public static UserAccount register(
      String username,
      String passwordHash,
      String nickname,
      String email,
      String phone,
      LocalDateTime occurredAt) {
    UserAccount account = new UserAccount();
    account.username = username;
    account.passwordHash = passwordHash;
    account.nickname = nickname == null ? username : nickname;
    account.avatar = "";
    account.email = email;
    account.phone = phone;
    account.signature = "";
    account.status = UserAccountStatus.ACTIVE;
    account.statusVersion = 1L;
    account.selfDestructPolicy = SelfDestructPolicy.OFF;
    account.selfDestructAt = null;
    // 首次登录时刻是业务字段；记账列由端口 save(..., occurredAt) 写入。
    account.lastLoginAt = occurredAt;
    account.role = UserAccountRole.USER;
    return account;
  }

  public void updateProfile(
      String nickname,
      String avatar,
      String email,
      String phone,
      String signature) {
    if (nickname != null) {
      this.nickname = nickname;
    }
    if (avatar != null) {
      this.avatar = avatar;
    }
    if (email != null) {
      this.email = email;
    }
    if (phone != null) {
      this.phone = phone;
    }
    if (signature != null) {
      this.signature = signature;
    }
  }

  public void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
    this.statusVersion++;
  }

  public void invalidateAuthentication() {
    this.statusVersion++;
  }

  /** 记录一次登录（或注册）：刷新最近登录时间，并按当前策略重算自毁截止时间。 */
  public void recordLogin(LocalDateTime occurredAt) {
    this.lastLoginAt = occurredAt;
    this.selfDestructAt = selfDestructPolicy.deadlineAfter(occurredAt);
  }

  /** 更新自毁策略：关闭则清空截止时间；开启则按最近活跃时间重算截止时间。 */
  public void setSelfDestructPolicy(SelfDestructPolicy policy, LocalDateTime occurredAt) {
    this.selfDestructPolicy = policy == null ? SelfDestructPolicy.OFF : policy;
    LocalDateTime activeAt = lastLoginAt != null ? lastLoginAt : occurredAt;
    this.selfDestructAt = this.selfDestructPolicy.deadlineAfter(activeAt);
  }

  /** 用户主动注销：保留账号行和注销状态，不修改用户名。 */
  public void markCancelled(String replacementPasswordHash) {
    this.status = UserAccountStatus.DISABLED;
    this.statusVersion++;
    this.passwordHash = replacementPasswordHash;
    this.nickname = "已注销用户";
    this.avatar = "";
    this.email = "";
    this.phone = "";
    this.signature = "";
    this.selfDestructPolicy = SelfDestructPolicy.OFF;
    this.selfDestructAt = null;
  }

  public boolean changeStatus(UserAccountStatus targetStatus) {
    if (this.status == targetStatus) {
      return false;
    }
    this.status = targetStatus;
    this.statusVersion++;
    return true;
  }

  public Long getId() { return id; }
  public String getUsername() { return username; }
  public String getNickname() { return nickname; }
  public String getAvatar() { return avatar; }
  public String getPasswordHash() { return passwordHash; }
  public String getEmail() { return email; }
  public String getPhone() { return phone; }
  public String getSignature() { return signature; }
  public UserAccountStatus getStatus() { return status; }
  public long getStatusVersion() { return statusVersion; }
  public SelfDestructPolicy getSelfDestructPolicy() { return selfDestructPolicy; }
  public LocalDateTime getSelfDestructAt() { return selfDestructAt; }
  public LocalDateTime getLastLoginAt() { return lastLoginAt; }
  public UserAccountRole getRole() { return role; }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(
      Long id,
      String username,
      String nickname,
      String avatar,
      String passwordHash,
      String email,
      String phone,
      String signature,
      UserAccountStatus status,
      long statusVersion,
      SelfDestructPolicy selfDestructPolicy,
      LocalDateTime selfDestructAt,
      LocalDateTime lastLoginAt,
      UserAccountRole role) {
    this.id = id;
    this.username = username;
    this.nickname = nickname;
    this.avatar = avatar;
    this.passwordHash = passwordHash;
    this.email = email;
    this.phone = phone;
    this.signature = signature;
    this.status = status;
    this.statusVersion = statusVersion;
    this.selfDestructPolicy = selfDestructPolicy == null ? SelfDestructPolicy.OFF : selfDestructPolicy;
    this.selfDestructAt = selfDestructAt;
    this.lastLoginAt = lastLoginAt;
    this.role = role;
  }

  public void assignId(Long id) { this.id = id; }
}
