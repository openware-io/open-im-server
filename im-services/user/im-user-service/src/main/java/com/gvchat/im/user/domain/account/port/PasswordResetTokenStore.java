package com.gvchat.im.user.domain.account.port;

import java.time.Duration;
import java.util.Optional;

/** 密码重置令牌仓库：短时令牌 → 用户标识的映射，带过期时间。 */
public interface PasswordResetTokenStore {
  void put(String token, long userId, Duration ttl);

  Optional<Long> findUserId(String token);

  void remove(String token);
}
