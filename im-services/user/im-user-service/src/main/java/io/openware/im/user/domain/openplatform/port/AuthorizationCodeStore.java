package io.openware.im.user.domain.openplatform.port;

import io.openware.im.user.domain.openplatform.model.AuthorizationCode;
import java.util.Optional;

/** 授权码存储端口：短效保存授权码，换取 token 后一次性消费。 */
public interface AuthorizationCodeStore {
  void save(AuthorizationCode authorizationCode);

  Optional<AuthorizationCode> findByCode(String code);

  void remove(String code);

  /** Atomically consumes a code. Implementations should use Redis GETDEL or equivalent. */
  default Optional<AuthorizationCode> consume(String code) {
    Optional<AuthorizationCode> value = findByCode(code);
    value.ifPresent(ignored -> remove(code));
    return value;
  }
}
