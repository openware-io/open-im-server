package io.openware.im.user.infra.redis;

import io.openware.common.constant.RedisKeys;
import io.openware.im.user.domain.account.port.PasswordResetTokenStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 密码重置令牌 Redis 实现：令牌 → 用户标识，带 TTL 自动过期。 */
@Component
@RequiredArgsConstructor
public class RedisPasswordResetTokenStore implements PasswordResetTokenStore {
  private final StringRedisTemplate redisTemplate;

  @Override
  public void put(String token, long userId, Duration ttl) {
    redisTemplate.opsForValue().set(RedisKeys.PASSWORD_RESET_TOKEN + token, String.valueOf(userId), ttl);
  }

  @Override
  public Optional<Long> findUserId(String token) {
    String raw = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_RESET_TOKEN + token);
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(raw));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  @Override
  public void remove(String token) {
    redisTemplate.delete(RedisKeys.PASSWORD_RESET_TOKEN + token);
  }
}
