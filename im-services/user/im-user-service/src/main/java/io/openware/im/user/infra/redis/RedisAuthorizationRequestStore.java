package io.openware.im.user.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.user.domain.openplatform.model.AuthorizationRequest;
import io.openware.im.user.domain.openplatform.port.AuthorizationRequestStore;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** OAuth 授权事务票据 Redis 实现：TTL 300s。 */
@Slf4j
@Component
public class RedisAuthorizationRequestStore implements AuthorizationRequestStore {
  private static final String KEY_PREFIX = "im:oauth:request:";
  private static final Duration TTL = Duration.ofSeconds(300);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisAuthorizationRequestStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void save(AuthorizationRequest request) {
    redisTemplate.opsForValue().set(KEY_PREFIX + request.requestId(), toJson(request), TTL);
  }

  @Override
  public Optional<AuthorizationRequest> findByRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return Optional.empty();
    }
    String raw = redisTemplate.opsForValue().get(KEY_PREFIX + requestId);
    return raw == null ? Optional.empty() : Optional.ofNullable(fromJson(raw));
  }

  @Override
  public void remove(String requestId) {
    if (requestId != null && !requestId.isBlank()) {
      redisTemplate.delete(KEY_PREFIX + requestId);
    }
  }

  private String toJson(AuthorizationRequest value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize OAuth authorization request", ex);
    }
  }

  private AuthorizationRequest fromJson(String raw) {
    try {
      return objectMapper.readValue(raw, AuthorizationRequest.class);
    } catch (JsonProcessingException ex) {
      log.warn("OAuth 授权事务票据反序列化失败，忽略, raw length={}", raw.length(), ex);
      return null;
    }
  }
}
