package io.openware.im.user.domain.social.model;

/**
 * 好友关系：单向记录（A→B），双方互为好友需要两条记录。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定。
 * 应用层需要它们（响应回显 + 授权版本号），经 {@code FriendRelationRepository.Persisted} 的
 * {@code AuditSnapshot} 带入；管理端列表是只读聚合查询，按 L266-279 走 {@code FriendRelationView} 投影。
 *
 * <p><b>注意 {@code createdAt}/{@code updatedAt} 还有一层语义</b>：{@code FriendAuthorizationQueryService}
 * 用 {@code updatedAt != null ? updatedAt : createdAt} 的 epoch 毫秒作为**私信授权版本号**
 * （跨服务供 im-message-service 判定）。因此载体必须同时提供两个时刻，且回退顺序不得改变——
 * 该行为已由 {@code FriendAuthorizationQueryServiceTest} 锁定。
 */
public class FriendRelation {
  private Long id;
  private Long userId;
  private Long friendId;
  private String remark;
  private String groupName;
  private FriendStatus status;

  public static FriendRelation create(Long userId, Long friendId) {
    FriendRelation relation = new FriendRelation();
    relation.userId = userId;
    relation.friendId = friendId;
    relation.remark = "";
    relation.groupName = "";
    relation.status = FriendStatus.NORMAL;
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

  /** 恢复/重新激活好友关系为 normal，用于删除后重建或拉黑后再接受时的 upsert。 */
  public void reactivate() {
    this.status = FriendStatus.NORMAL;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long userId, Long friendId, String remark, String groupName, FriendStatus status) {
    this.id = id;
    this.userId = userId;
    this.friendId = friendId;
    this.remark = remark;
    this.groupName = groupName;
    this.status = status;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getFriendId() { return friendId; }
  public String getRemark() { return remark; }
  public String getGroupName() { return groupName; }
  public FriendStatus getStatus() { return status; }
  public void assignId(Long id) { this.id = id; }
}
