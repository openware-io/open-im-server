package io.openware.im.user.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.user.domain.openplatform.model.OauthAccessToken;
import io.openware.im.user.domain.openplatform.model.OauthRefreshToken;
import io.openware.im.user.domain.openplatform.port.OauthTokenStore;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * OAuth 令牌 Redis 实现：access_token TTL 7200s、refresh_token TTL 30 天。
 *
 * <p>除按应用聚合的索引（app 级撤销/用户撤销）外，还维护按 family 聚合的索引：
 * 同一次授权/轮换链派生的 token 共享 familyId，refresh 重放时仅撤该家族。
 */
@Slf4j
@Component
public class RedisOauthTokenStore implements OauthTokenStore {
  private static final String ACCESS_KEY_PREFIX = "im:oauth:access:";
  private static final String REFRESH_KEY_PREFIX = "im:oauth:refresh:";
  private static final String ACCESS_INDEX_PREFIX = "im:oauth:access:index:";
  private static final String REFRESH_INDEX_PREFIX = "im:oauth:refresh:index:";
  private static final String ACCESS_FAMILY_PREFIX = "im:oauth:access:family:";
  private static final String REFRESH_FAMILY_PREFIX = "im:oauth:refresh:family:";
  private static final String REFRESH_USED_PREFIX = "im:oauth:refresh:used:";
  /** 遗留/无 family 信息的已用标记取值（旧格式恒为 "1"）。 */
  private static final String LEGACY_USED_MARKER = "1";
  private static final Duration ACCESS_TTL = Duration.ofSeconds(7200);
  private static final Duration REFRESH_TTL = Duration.ofDays(30);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisOauthTokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void saveAccessToken(String token, OauthAccessToken accessToken) {
    redisTemplate.opsForValue().set(ACCESS_KEY_PREFIX + token, toJson(accessToken), ACCESS_TTL);
    redisTemplate.opsForSet().add(ACCESS_INDEX_PREFIX + accessToken.appId(), token);
    redisTemplate.expire(ACCESS_INDEX_PREFIX + accessToken.appId(), ACCESS_TTL);
    if (accessToken.familyId() != null) {
      redisTemplate.opsForSet().add(ACCESS_FAMILY_PREFIX + accessToken.familyId(), token);
      redisTemplate.expire(ACCESS_FAMILY_PREFIX + accessToken.familyId(), ACCESS_TTL);
    }
  }

  @Override
  public Optional<OauthAccessToken> findAccessToken(String token) {
    String raw = redisTemplate.opsForValue().get(ACCESS_KEY_PREFIX + token);
    if (raw == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(fromJson(raw, OauthAccessToken.class));
  }

  @Override
  public void removeAccessToken(String token) {
    OauthAccessToken existing = findAccessToken(token).orElse(null);
    redisTemplate.delete(ACCESS_KEY_PREFIX + token);
    if (existing != null) {
      redisTemplate.opsForSet().remove(ACCESS_INDEX_PREFIX + existing.appId(), token);
      if (existing.familyId() != null) {
        redisTemplate.opsForSet().remove(ACCESS_FAMILY_PREFIX + existing.familyId(), token);
      }
    }
  }

  @Override
  public void saveRefreshToken(String token, OauthRefreshToken refreshToken) {
    redisTemplate.opsForValue().set(REFRESH_KEY_PREFIX + token, toJson(refreshToken), REFRESH_TTL);
    redisTemplate.opsForSet().add(REFRESH_INDEX_PREFIX + refreshToken.appId(), token);
    redisTemplate.expire(REFRESH_INDEX_PREFIX + refreshToken.appId(), REFRESH_TTL);
    if (refreshToken.familyId() != null) {
      redisTemplate.opsForSet().add(REFRESH_FAMILY_PREFIX + refreshToken.familyId(), token);
      redisTemplate.expire(REFRESH_FAMILY_PREFIX + refreshToken.familyId(), REFRESH_TTL);
    }
  }

  @Override
  public Optional<OauthRefreshToken> findRefreshToken(String token) {
    String raw = redisTemplate.opsForValue().get(REFRESH_KEY_PREFIX + token);
    if (raw == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(fromJson(raw, OauthRefreshToken.class));
  }

  @Override
  public void removeRefreshToken(String token) {
    OauthRefreshToken existing = findRefreshToken(token).orElse(null);
    redisTemplate.delete(REFRESH_KEY_PREFIX + token);
    if (existing != null) {
      redisTemplate.opsForSet().remove(REFRESH_INDEX_PREFIX + existing.appId(), token);
      if (existing.familyId() != null) {
        redisTemplate.opsForSet().remove(REFRESH_FAMILY_PREFIX + existing.familyId(), token);
      }
    }
    // 已用标记记录家族 id：轮换作废后若该旧 refresh 被重放，按家族撤销而不误伤整应用。
    redisTemplate.opsForValue().set(REFRESH_USED_PREFIX + token,
        existing != null && existing.familyId() != null ? existing.familyId() : LEGACY_USED_MARKER, REFRESH_TTL);
  }

  @Override
  public boolean wasRefreshTokenUsed(String token) {
    return token != null && Boolean.TRUE.equals(redisTemplate.hasKey(REFRESH_USED_PREFIX + token));
  }

  @Override
  public Optional<String> usedRefreshTokenFamily(String token) {
    if (token == null) {
      return Optional.empty();
    }
    String familyId = redisTemplate.opsForValue().get(REFRESH_USED_PREFIX + token);
    if (familyId == null || familyId.isBlank() || LEGACY_USED_MARKER.equals(familyId)) {
      return Optional.empty();
    }
    return Optional.of(familyId);
  }

  @Override
  public void revokeApplication(String appId) {
    revokeIndexedTokens(ACCESS_INDEX_PREFIX + appId, ACCESS_KEY_PREFIX, OauthAccessToken.class);
    revokeIndexedTokens(REFRESH_INDEX_PREFIX + appId, REFRESH_KEY_PREFIX, OauthRefreshToken.class);
  }

  @Override
  public void revokeApplicationUser(String appId, long userId) {
    revokeMatchingTokens(ACCESS_INDEX_PREFIX + appId, ACCESS_KEY_PREFIX, userId, OauthAccessToken.class);
    revokeMatchingTokens(REFRESH_INDEX_PREFIX + appId, REFRESH_KEY_PREFIX, userId, OauthRefreshToken.class);
  }

  @Override
  public void revokeFamily(String familyId) {
    if (familyId == null || familyId.isBlank()) {
      return;
    }
    revokeIndexedTokens(ACCESS_FAMILY_PREFIX + familyId, ACCESS_KEY_PREFIX, OauthAccessToken.class);
    revokeIndexedTokens(REFRESH_FAMILY_PREFIX + familyId, REFRESH_KEY_PREFIX, OauthRefreshToken.class);
  }

  /** 删除索引集合内全部 token，并对偶清理其 app 索引与 family 索引成员，最后删除索引 key。 */
  private void revokeIndexedTokens(String indexKey, String tokenKeyPrefix, Class<?> type) {
    var tokens = redisTemplate.opsForSet().members(indexKey);
    if (tokens != null && !tokens.isEmpty()) {
      for (String token : tokens) {
        String raw = redisTemplate.opsForValue().get(tokenKeyPrefix + token);
        if (raw != null) {
          Object value = fromJson(raw, type);
          if (value instanceof OauthAccessToken access) {
            redisTemplate.opsForSet().remove(ACCESS_INDEX_PREFIX + access.appId(), token);
            if (access.familyId() != null) {
              redisTemplate.opsForSet().remove(ACCESS_FAMILY_PREFIX + access.familyId(), token);
            }
          } else if (value instanceof OauthRefreshToken refresh) {
            redisTemplate.opsForSet().remove(REFRESH_INDEX_PREFIX + refresh.appId(), token);
            if (refresh.familyId() != null) {
              redisTemplate.opsForSet().remove(REFRESH_FAMILY_PREFIX + refresh.familyId(), token);
            }
          }
        }
        redisTemplate.delete(tokenKeyPrefix + token);
      }
    }
    redisTemplate.delete(indexKey);
  }

  private <T> void revokeMatchingTokens(String indexKey, String tokenKeyPrefix, long userId, Class<T> type) {
    var tokens = redisTemplate.opsForSet().members(indexKey);
    if (tokens == null || tokens.isEmpty()) {
      return;
    }
    for (String token : tokens) {
      String raw = redisTemplate.opsForValue().get(tokenKeyPrefix + token);
      if (raw == null) {
        redisTemplate.opsForSet().remove(indexKey, token);
        continue;
      }
      T value = fromJson(raw, type);
      if (value == null) {
        continue;
      }
      if (value instanceof OauthAccessToken access) {
        if (access.userId() == userId) {
          deleteTokenWithIndexes(tokenKeyPrefix, token, access.appId(), access.familyId());
        }
      } else {
        OauthRefreshToken refresh = (OauthRefreshToken) value;
        if (refresh.userId() == userId) {
          deleteTokenWithIndexes(tokenKeyPrefix, token, refresh.appId(), refresh.familyId());
        }
      }
    }
  }

  private void deleteTokenWithIndexes(String tokenKeyPrefix, String token, String appId, String familyId) {
    redisTemplate.delete(tokenKeyPrefix + token);
    redisTemplate.opsForSet().remove(ACCESS_INDEX_PREFIX + appId, token);
    redisTemplate.opsForSet().remove(REFRESH_INDEX_PREFIX + appId, token);
    if (familyId != null) {
      redisTemplate.opsForSet().remove(ACCESS_FAMILY_PREFIX + familyId, token);
      redisTemplate.opsForSet().remove(REFRESH_FAMILY_PREFIX + familyId, token);
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize OAuth token", ex);
    }
  }

  private <T> T fromJson(String raw, Class<T> type) {
    try {
      return objectMapper.readValue(raw, type);
    } catch (JsonProcessingException ex) {
      log.warn("OAuth token 反序列化失败，忽略, type={}", type.getSimpleName(), ex);
      return null;
    }
  }
}
