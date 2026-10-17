package io.openware.im.user.domain.openplatform.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/**
 * 开放平台应用：应用级授权主体，登记回调地址与授权范围，应用密钥仅存哈希。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；它们在本服务里没有读者。
 * 登记方是系统本身（{@code createdBy} 原为常量 0L），适配器直接按此口径填充。
 *
 * <p><b>与业务字段的区别</b>：{@code reviewedBy}/{@code reviewedAt} 是**审核事实**（谁在何时审的），
 * 留在模型上，因此 {@code approve}/{@code reject} 的 {@code reviewerId}/{@code occurredAt} 参数保留。
 * 其余迁移方法（{@code resetSecret}/{@code revoke}/{@code updateInfo}）的 {@code occurredAt}
 * 原本只用于填 {@code updatedAt}，已随审计列一并移除。
 */
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

  /** 第三方申请：进入待审核（PENDING），密钥在审核通过时才分配（appSecretHash 为空）。 */
  public static OpenApplication register(String appId, String appName, String subjectName, String appType,
      String callbackUrl, String appSecretHash, List<String> scopes) {
    OpenApplication app = new OpenApplication();
    app.appId = appId;
    app.appName = appName;
    app.subjectName = subjectName;
    app.appType = appType;
    app.callbackUrl = callbackUrl;
    app.appSecretHash = appSecretHash;
    app.status = OpenApplicationStatus.PENDING;
    app.scopes = scopes;
    return app;
  }

  /** 审核通过：写入应用密钥哈希，状态置 APPROVED 并记录审核人/时间。 */
  public void approve(String appSecretHash, Long reviewerId, LocalDateTime occurredAt) {
    this.appSecretHash = appSecretHash;
    this.status = OpenApplicationStatus.APPROVED;
    this.reviewedBy = reviewerId == null ? 0L : reviewerId;
    this.reviewedAt = occurredAt;
  }

  /** 审核驳回：记录驳回原因，状态置 REJECTED。 */
  public void reject(String reason, Long reviewerId, LocalDateTime occurredAt) {
    this.rejectReason = reason;
    this.status = OpenApplicationStatus.REJECTED;
    this.reviewedBy = reviewerId == null ? 0L : reviewerId;
    this.reviewedAt = occurredAt;
  }

  /** 重置密钥：仅对 APPROVED 应用有效，写入新密钥哈希。 */
  public void resetSecret(String appSecretHash) {
    this.appSecretHash = appSecretHash;
  }

  /** 撤销应用：状态置为 SUSPENDED，授权范围保留仅做登记。 */
  public void revoke() {
    this.status = OpenApplicationStatus.SUSPENDED;
  }

  /** 更新应用信息：仅允许变更 callbackUrl 与 scopes，appId/appSecret/status 保持不变。 */
  public void updateInfo(String callbackUrl, List<String> scopes) {
    this.callbackUrl = callbackUrl;
    this.scopes = scopes;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, String appId, String appName, String subjectName, String appType, String callbackUrl,
      String appSecretHash, OpenApplicationStatus status, List<String> scopes, String rejectReason,
      Long reviewedBy, LocalDateTime reviewedAt) {
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
  }

  public void assignId(Long id) {
    this.id = id;
  }
}
