package io.openware.group.idaas.infra.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 集团 IDaaS 登录态存储（Redis）：
 * - 会话（session）：登录后服务端持有，浏览器仅持有 HttpOnly Cookie 中的 sessionId，杜绝 localStorage 伪造/窃取。
 * - SSO 一次性票据（ticket）：门户跳转后台时签发，60 秒有效、用后即焚，避免在 URL 透传长期 JWT。
 */
@Component
public class IdaasSessionStore {

  private static final String SESSION_PREFIX = "idaas:session:";
  private static final String TICKET_PREFIX = "idaas:sso-ticket:";
  public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(24);
  private static final Duration TICKET_TTL = Duration.ofSeconds(60);

  private final StringRedisTemplate redisTemplate;

  public IdaasSessionStore(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** 创建登录会话，返回 sessionId。 */
  public String createSession(long accountId, String username) {
    return createSession(accountId, username, DEFAULT_SESSION_TTL);
  }

  public String createSession(long accountId, String username, Duration ttl) {
    String sessionId = UUID.randomUUID().toString().replace("-", "");
    long expiresAt = System.currentTimeMillis() + ttl.toMillis();
    redisTemplate.opsForValue().set(SESSION_PREFIX + sessionId,
        accountId + ":" + username + ":" + expiresAt, ttl);
    return sessionId;
  }

  /** 按 sessionId 查会话；不存在/过期返回 empty。 */
  public Optional<IdaasUser> findSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    return parse(redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId));
  }

  public void deleteSession(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      redisTemplate.delete(SESSION_PREFIX + sessionId);
    }
  }

  /** 基于有效会话签发一次性 SSO 票据。 */
  public String issueTicket(String sessionId) {
    IdaasUser user = findSession(sessionId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "AUTH_INVALID_SESSION", "会话无效或已过期"));
    String ticket = UUID.randomUUID().toString().replace("-", "");
    long expiresAt = sessionExpiresAt(sessionId);
    long ticketExpiresAt = Math.min(expiresAt, System.currentTimeMillis() + TICKET_TTL.toMillis());
    Duration ticketTtl = Duration.ofMillis(Math.max(1, ticketExpiresAt - System.currentTimeMillis()));
    redisTemplate.opsForValue().set(TICKET_PREFIX + ticket,
        user.accountId() + ":" + user.username() + ":" + expiresAt, ticketTtl);
    return ticket;
  }

  /** 消费一次性 SSO 票据（用后即焚）。 */
  public Optional<IdaasUser> consumeTicket(String ticket) {
    if (ticket == null || ticket.isBlank()) {
      return Optional.empty();
    }
    String key = TICKET_PREFIX + ticket;
    String raw = redisTemplate.opsForValue().get(key);
    if (raw != null) {
      redisTemplate.delete(key);
    }
    return parse(raw);
  }

  public long sessionExpiresAt(String sessionId) {
    String raw = redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId);
    if (raw == null) return 0;
    int last = raw.lastIndexOf(':');
    if (last >= 0) {
      try { return Long.parseLong(raw.substring(last + 1)); } catch (NumberFormatException ignored) { }
    }
    Long remaining = redisTemplate.getExpire(SESSION_PREFIX + sessionId, TimeUnit.MILLISECONDS);
    return remaining == null || remaining <= 0 ? 0 : System.currentTimeMillis() + remaining;
  }

  private Optional<IdaasUser> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    int idx = raw.indexOf(':');
    if (idx < 0) {
      return Optional.empty();
    }
    try {
      long accountId = Long.parseLong(raw.substring(0, idx));
      String remainder = raw.substring(idx + 1);
      int expirySeparator = remainder.lastIndexOf(':');
      long expiresAt = 0;
      String username = remainder;
      if (expirySeparator > 0) {
        try {
          expiresAt = Long.parseLong(remainder.substring(expirySeparator + 1));
          username = remainder.substring(0, expirySeparator);
        } catch (NumberFormatException ignored) { }
      }
      return Optional.of(new IdaasUser(accountId, username, expiresAt));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** 会话/票据解析出的用户。 */
  public record IdaasUser(long accountId, String username, long expiresAt) {}
}
