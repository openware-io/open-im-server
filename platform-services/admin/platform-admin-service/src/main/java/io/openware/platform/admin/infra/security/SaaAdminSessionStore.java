package io.openware.platform.admin.infra.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.admin.domain.model.AdminRole;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * SaaS 后台登录态存储（Redis）：登录/SSO 后服务端持有会话，浏览器仅持有 HttpOnly Cookie 的 sessionId。
 */
@Component
public class SaaAdminSessionStore {

  private static final String PREFIX = "saas-admin:session:";
  private static final String CSRF_PREFIX = "saas-admin:csrf:";
  private static final DefaultRedisScript<Long> UPDATE_CONTEXT = new DefaultRedisScript<>("""
      local raw = redis.call('GET', KEYS[1])
      if not raw or redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
      local session = cjson.decode(raw)
      session.tenantContextToken = ARGV[1]
      session.selectedContextId = ARGV[2]
      redis.call('SET', KEYS[1], cjson.encode(session), 'XX', 'KEEPTTL')
      return 1
      """, Long.class);

  private final StringRedisTemplate redisTemplate;
  private final AdminTenantContextTokenSigner platformTokens;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SaaAdminSessionStore(StringRedisTemplate redisTemplate, AdminTenantContextTokenSigner platformTokens) {
    this.redisTemplate = redisTemplate;
    this.platformTokens = platformTokens;
  }

  /** 创建会话（默认有效期），返回 sessionId。 */
  public String createSession(AdminContext ctx) {
    return createSession(ctx, SessionTtl.DEFAULT);
  }

  /** 创建会话（指定有效期），返回 sessionId。 */
  public String createSession(AdminContext ctx, Duration ttl) {
    String sessionId = UUID.randomUUID().toString().replace("-", "");
    try {
      var node = objectMapper.createObjectNode();
      node.put("accountId", ctx.accountId());
      node.put("username", ctx.username());
      node.put("displayName", ctx.displayName() == null ? "" : ctx.displayName());
      node.put("role", ctx.role().name());
      node.put("expiresAt", System.currentTimeMillis() + ttl.toMillis());
      if (ctx.platformAccountId() != null) {
        node.put("platformAccountId", ctx.platformAccountId());
      }
      if (ctx.tenantContextToken() != null) {
        node.put("tenantContextToken", ctx.tenantContextToken());
      }
      // 平台运营：预置平台作用域上下文 token（tenantId=0 + scopeType=PLATFORM，有效期同会话）。
      // /api/v1/admin/platform/** 是 SESSION_ONLY 路由，运营未选择租户上下文时网关切无上下文可下发，
      // 下游领域服务的平台级动作就会丢操作人；这里签好由网关按需代发，签名校验仍在下游完成。
      if (isPlatformRole(ctx.role())) {
        node.put("platformContextToken",
            platformTokens.signPlatform(operatorAccountId(ctx), ttl));
      }
      redisTemplate.opsForValue().set(PREFIX + sessionId, objectMapper.writeValueAsString(node), ttl);
      return sessionId;
    } catch (Exception e) {
      throw new IllegalStateException("无法创建 SaaS 后台会话", e);
    }
  }

  /** 平台运营角色（超管/平台管理员）：只有这些角色的动作才允许以平台作用域上下文下发。 */
  private static boolean isPlatformRole(AdminRole role) {
    return role == AdminRole.SUPER_ADMIN || role == AdminRole.PLATFORM_ADMIN;
  }

  /** 操作人账号 ID：优先平台账号，退化为本地后台账号（与审计拦截器的 operatorId 口径一致）。 */
  private static long operatorAccountId(AdminContext ctx) {
    return ctx.platformAccountId() != null ? ctx.platformAccountId() : ctx.accountId();
  }

  /** 按 sessionId 查会话，返回管理员上下文。 */
  public Optional<AdminContext> findSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    String raw = redisTemplate.opsForValue().get(PREFIX + sessionId);
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode node = objectMapper.readTree(raw);
      Long platformAccountId = node.hasNonNull("platformAccountId")
          ? node.path("platformAccountId").asLong()
          : null;
      return Optional.of(new AdminContext(
          node.path("accountId").asLong(),
          node.path("username").asText(""),
          node.path("displayName").asText(""),
          AdminRole.valueOf(node.path("role").asText("")),
          platformAccountId,
          node.path("tenantContextToken").asText(null)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** 删除会话（退出登录）。 */
  public void deleteSession(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      redisTemplate.delete(PREFIX + sessionId);
      redisTemplate.delete(CSRF_PREFIX + sessionId);
    }
  }

  public void setActiveContext(String sessionId, String tenantContextToken, String contextId) {
    if (sessionId == null || sessionId.isBlank() || tenantContextToken == null || contextId == null) {
      throw new IllegalArgumentException("invalid SaaS admin context");
    }
    Long updated = redisTemplate.execute(UPDATE_CONTEXT, java.util.List.of(PREFIX + sessionId), tenantContextToken, contextId);
    if (!Long.valueOf(1).equals(updated)) throw new IllegalArgumentException("invalid SaaS admin session");
  }

  public String selectedContextId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) return null;
    String raw = redisTemplate.opsForValue().get(PREFIX + sessionId);
    if (raw == null) return null;
    try {
      return objectMapper.readTree(raw).path("selectedContextId").asText(null);
    } catch (Exception exception) {
      throw new IllegalStateException("无法读取 SaaS 后台上下文", exception);
    }
  }

  public String issueCsrfToken(String sessionId) {
    if (findSession(sessionId).isEmpty()) {
      throw new IllegalArgumentException("invalid SaaS admin session");
    }
    String token = UUID.randomUUID().toString().replace("-", "");
    redisTemplate.opsForValue().set(CSRF_PREFIX + sessionId, token, remainingTtl(sessionId));
    return token;
  }

  public boolean isCsrfTokenValid(String sessionId, String token) {
    if (sessionId == null || sessionId.isBlank() || token == null || token.isBlank()) {
      return false;
    }
    String expected = redisTemplate.opsForValue().get(CSRF_PREFIX + sessionId);
    return expected != null && java.security.MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private Duration remainingTtl(String sessionId) {
    Long remaining = redisTemplate.getExpire(PREFIX + sessionId, TimeUnit.MILLISECONDS);
    if (remaining == null || remaining <= 0) {
      return SessionTtl.DEFAULT;
    }
    return Duration.ofMillis(remaining);
  }
}
