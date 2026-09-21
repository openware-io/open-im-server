package com.gvchat.im.user.domain.account.model;

import java.time.LocalDateTime;

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
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

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
    account.lastLoginAt = occurredAt;
    account.role = UserAccountRole.USER;
    account.createdBy = 0L;
    account.createdAt = occurredAt;
    account.updatedBy = 0L;
    account.updatedAt = occurredAt;
    return account;
  }

  public void updateProfile(
      String nickname,
      String avatar,
      String email,
      String phone,
      String signature,
      LocalDateTime occurredAt) {
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
    this.updatedAt = occurredAt;
  }

  public void changePassword(String passwordHash, LocalDateTime occurredAt) {
    this.passwordHash = passwordHash;
    this.statusVersion++;
    this.updatedAt = occurredAt;
  }

  public void invalidateAuthentication(LocalDateTime occurredAt) {
    this.statusVersion++;
    this.updatedAt = occurredAt;
  }

  /** 记录一次登录（或注册）：刷新最近登录时间，并按当前策略重算自毁截止时间。 */
  public void recordLogin(LocalDateTime occurredAt) {
    this.lastLoginAt = occurredAt;
    this.selfDestructAt = selfDestructPolicy.deadlineAfter(occurredAt);
    this.updatedAt = occurredAt;
  }

  /** 更新自毁策略：关闭则清空截止时间；开启则按最近活跃时间重算截止时间。 */
  public void setSelfDestructPolicy(SelfDestructPolicy policy, LocalDateTime occurredAt) {
    this.selfDestructPolicy = policy == null ? SelfDestructPolicy.OFF : policy;
    LocalDateTime activeAt = lastLoginAt != null ? lastLoginAt : occurredAt;
    this.selfDestructAt = this.selfDestructPolicy.deadlineAfter(activeAt);
    this.updatedAt = occurredAt;
  }

  public void cancel(String replacementPasswordHash, String replacementUsername, LocalDateTime occurredAt) {
    this.status = UserAccountStatus.DISABLED;
    this.statusVersion++;
    this.username = replacementUsername;
    this.passwordHash = replacementPasswordHash;
    this.nickname = "已注销用户";
    this.avatar = "";
    this.email = "";
    this.phone = "";
    this.signature = "";
    this.selfDestructPolicy = SelfDestructPolicy.OFF;
    this.selfDestructAt = null;
    this.updatedAt = occurredAt;
  }

  public boolean changeStatus(UserAccountStatus targetStatus, Long operatorId, LocalDateTime occurredAt) {
    if (this.status == targetStatus) {
      return false;
    }
    this.status = targetStatus;
    this.statusVersion++;
    this.updatedBy = operatorId;
    this.updatedAt = occurredAt;
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
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }

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
      UserAccountRole role,
      Long createdBy,
      LocalDateTime createdAt,
      Long updatedBy,
      LocalDateTime updatedAt) {
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
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public void assignId(Long id) { this.id = id; }
}
