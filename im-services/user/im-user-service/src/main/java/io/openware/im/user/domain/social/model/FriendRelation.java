package io.openware.im.user.domain.social.model;

import java.time.LocalDateTime;

public class FriendRelation {
  private Long id;
  private Long userId;
  private Long friendId;
  private String remark;
  private String groupName;
  private FriendStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public static FriendRelation create(Long userId, Long friendId, LocalDateTime occurredAt) {
    FriendRelation relation = new FriendRelation();
    relation.userId = userId;
    relation.friendId = friendId;
    relation.remark = "";
    relation.groupName = "";
    relation.status = FriendStatus.NORMAL;
    relation.createdAt = occurredAt;
    return relation;
  }

  public void update(String remark, String groupName) {
    if (remark != null) {
      this.remark = remark;
    }
    if (groupName != null) {
      this.groupName = groupName;
    }
  }

  public void block() { this.status = FriendStatus.BLOCKED; }

  public void unblock() { this.status = FriendStatus.NORMAL; }

  /** 恢复/重新激活好友关系为 normal 并刷新时间，用于删除后重建或拉黑后再接受时的 upsert。 */
  public void reactivate(LocalDateTime occurredAt) {
    this.status = FriendStatus.NORMAL;
    this.updatedAt = occurredAt;
  }

  public void restore(Long id, Long userId, Long friendId, String remark, String groupName, FriendStatus status,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.friendId = friendId;
    this.remark = remark;
    this.groupName = groupName;
    this.status = status;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getFriendId() { return friendId; }
  public String getRemark() { return remark; }
  public String getGroupName() { return groupName; }
  public FriendStatus getStatus() { return status; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void assignId(Long id) { this.id = id; }
}
