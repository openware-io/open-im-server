package io.openware.im.user.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.user.domain.openplatform.model.AuthorizationCode;
import io.openware.im.user.domain.openplatform.port.AuthorizationCodeStore;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 授权码 Redis 实现：TTL 300s 短效，换取 token 后删除（一次性消费）。 */
@Slf4j
@Component
public class RedisAuthorizationCodeStore implements AuthorizationCodeStore {
  private static final String KEY_PREFIX = "im:oauth:code:";
  private static final Duration TTL = Duration.ofSeconds(300);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisAuthorizationCodeStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(AuthorizationCode authorizationCode) {
    redisTemplate.opsForValue().set(KEY_PREFIX + authorizationCode.code(), toJson(authorizationCode), TTL);
  }

  @Override
  public Optional<AuthorizationCode> findByCode(String code) {
    String raw = redisTemplate.opsForValue().get(KEY_PREFIX + code);
    if (raw == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(fromJson(raw));
  }

  @Override
  public Optional<AuthorizationCode> consume(String code) {
    String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code);
    if (raw == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(fromJson(raw));
  }

  @Override
  public void remove(String code) {
    redisTemplate.delete(KEY_PREFIX + code);
  }

  private String toJson(AuthorizationCode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize authorization code", ex);
    }
  }

  private AuthorizationCode fromJson(String raw) {
    try {
      return objectMapper.readValue(raw, AuthorizationCode.class);
    } catch (JsonProcessingException ex) {
      log.warn("授权码反序列化失败，忽略, raw length={}", raw.length(), ex);
      return null;
    }
  }
}
