package io.openware.im.user.domain.social.model;

/**
 * 好友申请聚合：记录发起方/接收方/附言与处理状态。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code FriendRequestRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 *
 * <p><b>重新申请为什么用独立端口方法</b>：好友申请列表按 {@code created_at} 倒序，
 * 「重新申请」必须把该行**排回最前**，也就是把 {@code created_at} 刷新为当前时刻。
 * 这是业务动作而非 {@code save} 的变体，所以由
 * {@code FriendRequestRepository.reapply(request)} 表达——领域模型因此不需要携带时刻。
 */
public class FriendRequest {
  private Long id;
  private Long fromUserId;
  private Long toUserId;
  private String message;
  private FriendRequestStatus status;

  public static FriendRequest create(Long fromUserId, Long toUserId, String message) {
    FriendRequest request = new FriendRequest();
    request.fromUserId = fromUserId;
    request.toUserId = toUserId;
    request.message = message == null ? "" : message;
    request.status = FriendRequestStatus.PENDING;
    return request;
  }

  public void handle(FriendRequestStatus status) {
    this.status = status;
  }

  /**
   * 重新申请时覆盖已有 pending：只刷新附言，状态保持 pending 语义。
   *
   * <p>申请时刻的刷新**不在这里**——它由 {@code FriendRequestRepository.reapply} 在写库时完成，
   * 因为那是持久化的记账列（standards/10 L299）。
   */
  public void overwrite(String message) {
    this.message = message == null ? "" : message;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long fromUserId, Long toUserId, String message, FriendRequestStatus status) {
    this.id = id;
    this.fromUserId = fromUserId;
    this.toUserId = toUserId;
    this.message = message;
    this.status = status;
  }

  public Long getId() { return id; }
  public Long getFromUserId() { return fromUserId; }
  public Long getToUserId() { return toUserId; }
  public String getMessage() { return message; }
  public FriendRequestStatus getStatus() { return status; }
  public void assignId(Long id) { this.id = id; }
}
