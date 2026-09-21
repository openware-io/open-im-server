package com.gvchat.im.user.domain.social.model;

import java.time.LocalDateTime;

public class FriendRequest {
  private Long id;
  private Long fromUserId;
  private Long toUserId;
  private String message;
  private FriendRequestStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public static FriendRequest create(Long fromUserId, Long toUserId, String message, LocalDateTime occurredAt) {
    FriendRequest request = new FriendRequest();
    request.fromUserId = fromUserId;
    request.toUserId = toUserId;
    request.message = message == null ? "" : message;
    request.status = FriendRequestStatus.PENDING;
    request.createdAt = occurredAt;
    request.updatedAt = occurredAt;
    return request;
  }

  public void handle(FriendRequestStatus status, LocalDateTime occurredAt) {
    this.status = status;
    this.updatedAt = occurredAt;
  }

  /** 重新申请时覆盖已有 pending：刷新附言与时间，保持单行幂等并重新触发通知。 */
  public void overwrite(String message, LocalDateTime occurredAt) {
    this.message = message == null ? "" : message;
    this.createdAt = occurredAt;
    this.updatedAt = occurredAt;
  }

  public void restore(Long id, Long fromUserId, Long toUserId, String message, FriendRequestStatus status,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.fromUserId = fromUserId;
    this.toUserId = toUserId;
    this.message = message;
    this.status = status;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getFromUserId() { return fromUserId; }
  public Long getToUserId() { return toUserId; }
  public String getMessage() { return message; }
  public FriendRequestStatus getStatus() { return status; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void assignId(Long id) { this.id = id; }
}
