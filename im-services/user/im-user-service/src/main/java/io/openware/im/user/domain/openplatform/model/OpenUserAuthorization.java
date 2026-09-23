package io.openware.im.user.domain.openplatform.model;

import java.time.LocalDateTime;
import lombok.Getter;

/** 开放平台用户级授权：记录某应用对某用户的授权范围与派生 open_id。 */
@Getter
public class OpenUserAuthorization {
  private Long id;
  private Long applicationId;
  private Long userId;
  private String openId;
  private String scope;
  private OpenUserAuthorizationStatus status;
  private LocalDateTime authorizedAt;
  private LocalDateTime revokedAt;

  public static OpenUserAuthorization grant(Long applicationId, Long userId, String openId, String scope,
      LocalDateTime occurredAt) {
    OpenUserAuthorization auth = new OpenUserAuthorization();
    auth.applicationId = applicationId;
    auth.userId = userId;
    auth.openId = openId;
    auth.scope = scope;
    auth.status = OpenUserAuthorizationStatus.ACTIVE;
    auth.authorizedAt = occurredAt;
    auth.revokedAt = null;
    return auth;
  }

  /** 重复授权：刷新授权范围与 open_id，保持唯一授权记录。 */
  public void reauthorize(String openId, String scope, LocalDateTime occurredAt) {
    this.openId = openId;
    this.scope = scope;
    this.status = OpenUserAuthorizationStatus.ACTIVE;
    this.authorizedAt = occurredAt;
    this.revokedAt = null;
  }

  public void revoke(LocalDateTime occurredAt) {
    this.status = OpenUserAuthorizationStatus.REVOKED;
    this.revokedAt = occurredAt;
  }

  public void restore(Long id, Long applicationId, Long userId, String openId, String scope,
      OpenUserAuthorizationStatus status, LocalDateTime authorizedAt, LocalDateTime revokedAt) {
    this.id = id;
    this.applicationId = applicationId;
    this.userId = userId;
    this.openId = openId;
    this.scope = scope;
    this.status = status;
    this.authorizedAt = authorizedAt;
    this.revokedAt = revokedAt;
  }

  public void assignId(Long id) {
    this.id = id;
  }
}
