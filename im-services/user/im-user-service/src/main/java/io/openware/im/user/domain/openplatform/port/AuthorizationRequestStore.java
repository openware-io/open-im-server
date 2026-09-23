package io.openware.im.user.domain.openplatform.port;

import io.openware.im.user.domain.openplatform.model.AuthorizationRequest;
import java.util.Optional;

/** OAuth 授权事务票据存储：短期有效，成功或拒绝后一次性消费。 */
public interface AuthorizationRequestStore {
  void save(AuthorizationRequest request);

  Optional<AuthorizationRequest> findByRequestId(String requestId);

  void remove(String requestId);
}
