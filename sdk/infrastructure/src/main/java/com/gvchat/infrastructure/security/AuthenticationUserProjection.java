package com.gvchat.infrastructure.security;

import com.gvchat.common.constant.RedisKeys;
import com.gvchat.common.enums.UserRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticationUserProjection {
  private static final String SEPARATOR = "|";

  private final StringRedisTemplate redisTemplate;

  public void save(AuthenticationUserSnapshot snapshot) {
    redisTemplate.opsForValue().set(key(snapshot.userId()), String.join(
        SEPARATOR,
        snapshot.username(),
        snapshot.role().name(),
        Boolean.toString(snapshot.active()),
        Long.toString(snapshot.authenticationVersion())));
  }

  public Optional<AuthenticationUserSnapshot> findByUserId(long userId) {
    String value = redisTemplate.opsForValue().get(key(userId));
    if (value == null) {
      return Optional.empty();
    }
    String[] fields = value.split("\\|", -1);
    if (fields.length != 4) {
      return Optional.empty();
    }
    try {
      return Optional.of(new AuthenticationUserSnapshot(
          userId,
          fields[0],
          UserRole.valueOf(fields[1]),
          Boolean.parseBoolean(fields[2]),
          Long.parseLong(fields[3])));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private static String key(long userId) {
    return RedisKeys.AUTHENTICATION_USER + userId;
  }
}
