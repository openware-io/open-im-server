package com.gvchat.im.user.domain.account.model;

import java.time.LocalDateTime;

/**
 * 用户密保问题实体：承载「密保问题 + 答案哈希」，用于密保问题找回密码。
 *
 * <p>答案以 bcrypt 哈希存储（复用 {@code PasswordHasher}），领域层只保留哈希与问题文本，
 * 答案校验由应用层调用哈希端口完成，避免领域层耦合加密实现。</p>
 */
public class UserSecurityQuestion {
  private Long id;
  private Long userId;
  private String question;
  private String answerHash;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  /** 首次设置密保问题：答案必须已哈希。 */
  public static UserSecurityQuestion set(Long userId, String question, String answerHash, LocalDateTime occurredAt) {
    UserSecurityQuestion entity = new UserSecurityQuestion();
    entity.userId = userId;
    entity.question = question;
    entity.answerHash = answerHash;
    entity.createdBy = userId;
    entity.createdAt = occurredAt;
    entity.updatedBy = userId;
    entity.updatedAt = occurredAt;
    return entity;
  }

  /** 更新密保问题与答案（答案必须已哈希）。 */
  public void update(String question, String answerHash, LocalDateTime occurredAt) {
    this.question = question;
    this.answerHash = answerHash;
    this.updatedAt = occurredAt;
  }

  /** 问题是否匹配：比较前去除首尾空白。 */
  public boolean matchesQuestion(String expected) {
    if (this.question == null || expected == null) {
      return false;
    }
    return this.question.trim().equals(expected.trim());
  }

  public void restore(Long id, Long userId, String question, String answerHash, Long createdBy,
      LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.question = question;
    this.answerHash = answerHash;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getQuestion() { return question; }
  public String getAnswerHash() { return answerHash; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }

  public void assignId(Long id) { this.id = id; }
}
