package com.gvchat.im.user.infra.persistence.openplatform.converter;

import com.gvchat.im.user.domain.openplatform.model.OpenApplication;
import com.gvchat.im.user.domain.openplatform.model.OpenApplicationStatus;
import com.gvchat.im.user.domain.openplatform.model.OpenUserAuthorization;
import com.gvchat.im.user.domain.openplatform.model.OpenUserAuthorizationStatus;
import com.gvchat.im.user.infra.persistence.openplatform.po.OpenApplicationPo;
import com.gvchat.im.user.infra.persistence.openplatform.po.OpenApplicationScopePo;
import com.gvchat.im.user.infra.persistence.openplatform.po.OpenUserAuthorizationPo;
import java.util.List;

public final class OpenPlatformPersistenceConverter {
  private OpenPlatformPersistenceConverter() {
  }

  public static OpenApplication toDomain(OpenApplicationPo po, List<String> scopes) {
    OpenApplication app = new OpenApplication();
    app.restore(po.getId(), po.getAppId(), po.getAppName(), po.getSubjectName(), po.getAppType(), po.getCallbackUrl(),
        po.getAppSecretHash(), OpenApplicationStatus.valueOf(po.getStatus().toUpperCase()), scopes,
        po.getRejectReason(), po.getReviewedBy(), po.getReviewedAt(),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    return app;
  }

  public static OpenApplicationPo toPo(OpenApplication app) {
    OpenApplicationPo po = new OpenApplicationPo();
    po.setId(app.getId());
    po.setAppId(app.getAppId());
    po.setAppName(app.getAppName());
    po.setSubjectName(app.getSubjectName());
    po.setAppType(app.getAppType());
    po.setCallbackUrl(app.getCallbackUrl());
    po.setAppSecretHash(app.getAppSecretHash());
    po.setStatus(app.getStatus().name());
    po.setRejectReason(app.getRejectReason());
    po.setReviewedBy(app.getReviewedBy());
    po.setReviewedAt(app.getReviewedAt());
    po.setCreatedBy(app.getCreatedBy());
    po.setCreatedAt(app.getCreatedAt());
    po.setUpdatedBy(app.getUpdatedBy());
    po.setUpdatedAt(app.getUpdatedAt());
    return po;
  }

  public static OpenApplicationScopePo toScopePo(Long applicationId, String scope) {
    OpenApplicationScopePo po = new OpenApplicationScopePo();
    po.setApplicationId(applicationId);
    po.setScope(scope);
    return po;
  }

  public static OpenUserAuthorization toDomain(OpenUserAuthorizationPo po) {
    OpenUserAuthorization auth = new OpenUserAuthorization();
    auth.restore(po.getId(), po.getApplicationId(), po.getUserId(), po.getOpenId(), po.getScope(),
        OpenUserAuthorizationStatus.valueOf(po.getStatus().toUpperCase()), po.getAuthorizedAt(), po.getRevokedAt());
    return auth;
  }

  public static OpenUserAuthorizationPo toPo(OpenUserAuthorization auth) {
    OpenUserAuthorizationPo po = new OpenUserAuthorizationPo();
    po.setId(auth.getId());
    po.setApplicationId(auth.getApplicationId());
    po.setUserId(auth.getUserId());
    po.setOpenId(auth.getOpenId());
    po.setScope(auth.getScope());
    po.setStatus(auth.getStatus().name());
    po.setAuthorizedAt(auth.getAuthorizedAt());
    po.setRevokedAt(auth.getRevokedAt());
    return po;
  }
}
