package io.openware.im.user.domain.openplatform.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.openplatform.model.OpenApplication;
import io.openware.im.user.domain.openplatform.model.OpenApplicationStatus;
import java.util.Optional;

public interface OpenApplicationRepository {
  Optional<OpenApplication> findByAppId(String appId);

  /** Returns whether the URI is exactly registered for the OIDC client. */
  default boolean isRedirectUriRegistered(String appId, String redirectUri) {
    return false;
  }

  OpenApplication save(OpenApplication application);

  /** 分页列应用（可按审核状态与接入类型过滤），按 id 倒序。 */
  PageResult<OpenApplication> list(OpenApplicationStatus status, String appType, int page, int pageSize);
}
