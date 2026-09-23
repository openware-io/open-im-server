package io.openware.im.user.domain.openplatform.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/** 开放平台应用：应用级授权主体，登记回调地址与授权范围，应用密钥仅存哈希。 */
@Getter
public class OpenApplication {
  private Long id;
  private String appId;
  private String appName;
  private String subjectName;
  private String appType;
  private String callbackUrl;
  private String appSecretHash;
  private OpenApplicationStatus status;
  private List<String> scopes;
  private String rejectReason;
  private Long reviewedBy;
  private LocalDateTime reviewedAt;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  /** 第三方申请：进入待审核（PENDING），密钥在审核通过时才分配（appSecretHash 为空）。 */
  public static OpenApplication register(String appId, String appName, String subjectName, String appType, String callbackUrl,
      String appSecretHash, List<String> scopes, LocalDateTime occurredAt) {
    OpenApplication app = new OpenApplication();
    app.appId = appId;
    app.appName = appName;
    app.subjectName = subjectName;
    app.appType = appType;
    app.callbackUrl = callbackUrl;
    app.appSecretHash = appSecretHash;
    app.status = OpenApplicationStatus.PENDING;
    app.scopes = scopes;
    app.createdBy = 0L;
    app.createdAt = occurredAt;
    app.updatedBy = 0L;
    app.updatedAt = occurredAt;
    return app;
  }

  /** 审核通过：写入应用密钥哈希，状态置 APPROVED 并记录审核人/时间。 */
  public void approve(String appSecretHash, Long reviewerId, LocalDateTime occurredAt) {
    this.appSecretHash = appSecretHash;
    this.status = OpenApplicationStatus.APPROVED;
    this.reviewedBy = reviewerId == null ? 0L : reviewerId;
    this.reviewedAt = occurredAt;
    this.updatedAt = occurredAt;
  }

  /** 审核驳回：记录驳回原因，状态置 REJECTED。 */
  public void reject(String reason, Long reviewerId, LocalDateTime occurredAt) {
    this.rejectReason = reason;
    this.status = OpenApplicationStatus.REJECTED;
    this.reviewedBy = reviewerId == null ? 0L : reviewerId;
    this.reviewedAt = occurredAt;
    this.updatedAt = occurredAt;
  }

  /** 重置密钥：仅对 APPROVED 应用有效，写入新密钥哈希。 */
  public void resetSecret(String appSecretHash, LocalDateTime occurredAt) {
    this.appSecretHash = appSecretHash;
    this.updatedAt = occurredAt;
  }

  /** 撤销应用：状态置为 SUSPENDED，授权范围保留仅做登记。 */
  public void revoke(LocalDateTime occurredAt) {
    this.status = OpenApplicationStatus.SUSPENDED;
    this.updatedAt = occurredAt;
  }

  /** 更新应用信息：仅允许变更 callbackUrl 与 scopes，appId/appSecret/status 保持不变。 */
  public void updateInfo(String callbackUrl, List<String> scopes, LocalDateTime occurredAt) {
    this.callbackUrl = callbackUrl;
    this.scopes = scopes;
    this.updatedAt = occurredAt;
  }

  public void restore(Long id, String appId, String appName, String subjectName, String appType, String callbackUrl,
      String appSecretHash, OpenApplicationStatus status, List<String> scopes, String rejectReason,
      Long reviewedBy, LocalDateTime reviewedAt, Long createdBy, LocalDateTime createdAt, Long updatedBy,
      LocalDateTime updatedAt) {
    this.id = id;
    this.appId = appId;
    this.appName = appName;
    this.subjectName = subjectName;
    this.appType = appType;
    this.callbackUrl = callbackUrl;
    this.appSecretHash = appSecretHash;
    this.status = status;
    this.scopes = scopes;
    this.rejectReason = rejectReason;
    this.reviewedBy = reviewedBy;
    this.reviewedAt = reviewedAt;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public void assignId(Long id) {
    this.id = id;
  }
}
