package io.openware.im.user.domain.openplatform.repository;

import io.openware.im.user.domain.openplatform.model.OpenUserAuthorization;
import java.util.List;
import java.util.Optional;

public interface OpenUserAuthorizationRepository {
  Optional<OpenUserAuthorization> findByApplicationAndUser(Long applicationId, Long userId);

  List<OpenUserAuthorization> findByApplicationId(Long applicationId);

  OpenUserAuthorization save(OpenUserAuthorization authorization);
}
