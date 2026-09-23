package io.openware.im.user.domain.account.repository;

import io.openware.im.user.domain.account.model.UserStatusOperation;
import java.util.Optional;

public interface UserStatusOperationRepository {
  Optional<UserStatusOperation> findByIdempotencyKey(String idempotencyKey);

  void save(UserStatusOperation operation, long expectedStatusVersion, Long operatorId, String reason);
}
