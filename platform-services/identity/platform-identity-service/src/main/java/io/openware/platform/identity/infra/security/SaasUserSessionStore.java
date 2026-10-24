package io.openware.platform.identity.infra.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed SaaS H5 session. Tokens and active tenant scope never leave the server. */
@Component
public class SaasUserSessionStore {
  public static final String PREFIX = "saas:user-session:";
  private static final String ACCOUNT_APP_PREFIX = "saas:user-sessions:";
  private static final String CSRF_PREFIX = "saas:user-csrf:";
  private static final Duration TTL = Duration.ofHours(8);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SaasUserSessionStore(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public String createSession(long accountId) {
    return createSession(accountId, null);
  }

  public String createSession(long accountId, String appId) {
    return createSession(accountId, appId, null, null, null);
  }

  public String createSession(long accountId, String appId, Long membershipId, Long tenantId,
                              String scopeType) {
    String sessionId = newSessionId();
    try {
      var value = objectMapper.createObjectNode();
      value.put("accountId", accountId);
      if (appId != null && !appId.isBlank()) value.put("appId", appId);
      if (membershipId != null) value.put("membershipId", membershipId);
      if (tenantId != null) value.put("tenantId", tenantId);
      if (scopeType != null && !scopeType.isBlank()) value.put("scopeType", scopeType);
      redisTemplate.opsForValue().set(PREFIX + sessionId, objectMapper.writeValueAsString(value), TTL);
      if (appId != null && !appId.isBlank()) {
        String indexKey = ACCOUNT_APP_PREFIX + accountId + ":" + appId;
        redisTemplate.opsForSet().add(indexKey, sessionId);
        redisTemplate.expire(indexKey, TTL);
      }
      return sessionId;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to create SaaS user session", exception);
    }
  }

  public Optional<UserSession> findSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    String raw = redisTemplate.opsForValue().get(PREFIX + sessionId);
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode value = objectMapper.readTree(raw);
      long accountId = value.path("accountId").asLong(-1);
      if (accountId <= 0) {
        return Optional.empty();
      }
      String contextToken = value.path("tenantContextToken").asText(null);
      String appId = value.path("appId").asText(null);
      Long membershipId = value.has("membershipId") ? value.path("membershipId").asLong() : null;
      Long tenantId = value.has("tenantId") ? value.path("tenantId").asLong() : null;
      String scopeType = value.path("scopeType").asText(null);
      return Optional.of(new UserSession(accountId, contextToken, appId, membershipId, tenantId, scopeType));
    // degradation: unusable stored session means no principal (fail-closed)
    } catch (Exception exception) {
      return Optional.empty();
    }
  }

  public void setActiveContext(String sessionId, String tenantContextToken) {
    setActiveContext(sessionId, tenantContextToken, null, null);
  }

  public void setActiveContext(String sessionId, String tenantContextToken, Long tenantId, String scopeType) {
    if (tenantContextToken == null || tenantContextToken.isBlank()) {
      throw new IllegalArgumentException("tenant context token is required");
    }
    findSession(sessionId).ifPresentOrElse(session -> {
      try {
        var value = objectMapper.createObjectNode();
        value.put("accountId", session.accountId());
        if (session.appId() != null && !session.appId().isBlank()) value.put("appId", session.appId());
        if (session.membershipId() != null) value.put("membershipId", session.membershipId());
        Long effectiveTenant = tenantId != null ? tenantId : session.tenantId();
        if (effectiveTenant != null) value.put("tenantId", effectiveTenant);
        String effectiveScope = scopeType != null ? scopeType : session.scopeType();
        if (effectiveScope != null && !effectiveScope.isBlank()) value.put("scopeType", effectiveScope);
        value.put("tenantContextToken", tenantContextToken);
        Duration remaining = remainingTtl(sessionId);
        value.put("expiresAt", System.currentTimeMillis() + remaining.toMillis());
        redisTemplate.opsForValue().set(PREFIX + sessionId, objectMapper.writeValueAsString(value), remaining);
      } catch (Exception exception) {
        throw new IllegalStateException("Unable to update SaaS user session", exception);
      }
    }, () -> { throw new IllegalArgumentException("invalid SaaS user session"); });
  }

  public String issueCsrfToken(String sessionId) {
    if (findSession(sessionId).isEmpty()) {
      throw new IllegalArgumentException("invalid SaaS user session");
    }
    String token = newSessionId();
    redisTemplate.opsForValue().set(CSRF_PREFIX + sessionId, token, remainingTtl(sessionId));
    return token;
  }

  public boolean isCsrfTokenValid(String sessionId, String token) {
    if (sessionId == null || sessionId.isBlank() || token == null || token.isBlank()) {
      return false;
    }
    String expected = redisTemplate.opsForValue().get(CSRF_PREFIX + sessionId);
    return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8));
  }

  public void deleteSession(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      findSession(sessionId).ifPresent(session -> {
        if (session.appId() != null && !session.appId().isBlank()) {
          redisTemplate.opsForSet().remove(
              ACCOUNT_APP_PREFIX + session.accountId() + ":" + session.appId(), sessionId);
        }
      });
      redisTemplate.delete(PREFIX + sessionId);
      redisTemplate.delete(CSRF_PREFIX + sessionId);
    }
  }

  /** 解绑/撤销应用时删除该账号在该 app 下的全部会话。 */
  public void deleteSessions(long accountId, String appId) {
    if (appId == null || appId.isBlank()) {
      return;
    }
    String indexKey = ACCOUNT_APP_PREFIX + accountId + ":" + appId;
    var sessionIds = redisTemplate.opsForSet().members(indexKey);
    if (sessionIds != null) {
      for (String sessionId : sessionIds) {
        redisTemplate.delete(PREFIX + sessionId);
        redisTemplate.delete(CSRF_PREFIX + sessionId);
      }
    }
    redisTemplate.delete(indexKey);
  }

  private Duration remainingTtl(String sessionId) {
    Long remaining = redisTemplate.getExpire(PREFIX + sessionId, TimeUnit.MILLISECONDS);
    return remaining == null || remaining <= 0 ? TTL : Duration.ofMillis(remaining);
  }

  private static String newSessionId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public record UserSession(long accountId, String tenantContextToken, String appId,
                            Long membershipId, Long tenantId, String scopeType) {
    public UserSession(long accountId, String tenantContextToken, String appId) {
      this(accountId, tenantContextToken, appId, null, null, null);
    }

    public UserSession(long accountId, String tenantContextToken) {
      this(accountId, tenantContextToken, null, null, null, null);
    }
  }
        }