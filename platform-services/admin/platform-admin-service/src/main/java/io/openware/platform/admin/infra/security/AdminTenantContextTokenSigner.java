package io.openware.platform.admin.infra.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Issues the same short-lived, audience-bound tenant context token as identity service. */
@Component
public class AdminTenantContextTokenSigner {
  /** 运营上下文 Token 不可用的统一业务码（与 StaffController / MediaImageController 保持一致）。 */
  private static final String CONTEXT_INVALID_CODE = "SAAS_CONTEXT_INVALID";
  private static final String CONTEXT_EXPIRED_MESSAGE = "运营上下文已过期，请重新登录";
  private static final String CONTEXT_INVALID_MESSAGE = "运营上下文无效，请重新登录";

  private final SecretKey secretKey;

  public AdminTenantContextTokenSigner(JwtProperties properties) {
    properties.validate();
    secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
  }

  public String sign(long accountId, long tenantId, Long organizationId, Long storeId,
                     int authorizationVersion, List<String> permissions) {
    return sign(accountId, tenantId, organizationId, storeId, authorizationVersion, permissions, null, null);
  }

  /**
   * 签发经营上下文 Token，并声明作用域类型。
   *
   * <p>{@code scopeType} 来自 IAM 上下文快照（平台账号为 {@code PLATFORM}），下游服务据此区分
   * 「平台视角（可跨租户）」与「租户视角（强制收敛）」，例如审计日志查询。缺失时下游按 TENANT 处理。
   *
   * <p>币种缺省 {@link Currency#DEFAULT}（USD）：老调用方不传币种时签出 USD，与「租户未配置即 USD」一致。
   */
  public String sign(long accountId, long tenantId, Long organizationId, Long storeId,
                     int authorizationVersion, List<String> permissions, String scopeType) {
    return sign(accountId, tenantId, organizationId, storeId, authorizationVersion, permissions, scopeType, null);
  }

  /**
   * 签发经营上下文 Token，写入币种 claim {@code currency}（规范 §3.1）。
   *
   * <p>非法/缺失值一律回退 USD；下游 {@code TenantContextFilter} 解析该 claim 并经旁路 holder
   * 暴露给 {@code CurrencyResolver}，老 token 无该 claim 时同样回退 USD。
   */
  public String sign(long accountId, long tenantId, Long organizationId, Long storeId,
                     int authorizationVersion, List<String> permissions, String scopeType, String currencyCode) {
    Date now = new Date();
    return Jwts.builder().subject(String.valueOf(accountId)).issuer("open-saas-identity")
        .audience().add("open-saas-services").and().claim("tenantId", tenantId)
        .claim("organizationId", organizationId).claim("storeId", storeId)
        .claim("authorizationVersion", authorizationVersion).claim("permissions", permissions)
        .claim("scopeType", scopeType)
        .claim("currency", Currency.parse(currencyCode).code())
        .issuedAt(now).expiration(Date.from(now.toInstant().plus(Duration.ofMinutes(30))))
        .signWith(secretKey).compact();
  }

  /**
   * 签发**平台作用域**上下文 Token（平台运营，无租户约束）：{@code tenantId=0} + {@code scopeType=PLATFORM}。
   *
   * <p>用途：{@code /api/v1/admin/platform/**} 是 SESSION_ONLY 路由，平台运营没有选择租户上下文时
   * 下游领域服务拿不到任何运营上下文，平台级动作（如 {@code tenant.create}）落库就没有操作人。
   * 会话建立时预先签发该平台 token 存入后台会话，网关在租户上下文缺省时代为下发：下游按签名校验后
   * 得到 {@code accountId = 平台账号}，操作人字段即被 {@code AuditClient} 自动补全。
   *
   * <p>与租户上下文同一套签名与校验（同一密钥/签发方/受众），并额外强制 {@code scopeType=PLATFORM}
   * 才允许 tenantId=0——不放松任何签名校验，也不允许租户账号冒充平台作用域。
   *
   * <p>有效期与后台会话一致：会话有效期内平台 token 也始终有效，避免「登录 30 分钟后平台级动作重新丢操作人」。
   */
  public String signPlatform(long accountId, Duration ttl) {
    Date now = new Date();
    long effectiveTtlMillis = ttl == null || ttl.isZero() || ttl.isNegative()
        ? Duration.ofMinutes(30).toMillis()
        : ttl.toMillis();
    return Jwts.builder().subject(String.valueOf(accountId)).issuer("open-saas-identity")
        .audience().add("open-saas-services").and()
        .claim("tenantId", 0L)
        .claim("authorizationVersion", 0)
        .claim("permissions", List.of())
        .claim("scopeType", io.openware.infrastructure.tenant.TenantContext.SCOPE_PLATFORM)
        .claim("currency", Currency.DEFAULT.code())
        .issuedAt(now).expiration(Date.from(now.toInstant().plusMillis(effectiveTtlMillis)))
        .signWith(secretKey).compact();
  }

  /**
   * 校验并解析会话内嵌的运营上下文 Token。
   *
   * <p>Token 过期/损坏一律转成 401 业务异常（见 {@link #contextUnavailable}）：调用方无需再自行
   * try/catch，也保证 {@code GET /admin/menus} 这类入口不会再因过期的上下文 Token 变成 500。
   *
   * @param token 会话中保存的运营上下文 Token
   * @return 解析出的租户上下文
   * @throws ApiException 401 + {@code SAAS_CONTEXT_INVALID}：Token 过期、签名/受众不符或结构损坏
   */
  public io.openware.infrastructure.tenant.TenantContext verify(String token) {
    Claims claims = parseContextClaims(token);
    Number tenant = claims.get("tenantId", Number.class);
    Long accountId = parseAccountId(claims.getSubject());
    if (tenant == null || accountId == null) {
      throw contextUnavailable(CONTEXT_INVALID_MESSAGE);
    }
    Number organization = claims.get("organizationId", Number.class);
    Number store = claims.get("storeId", Number.class);
    return new io.openware.infrastructure.tenant.TenantContext(tenant.longValue(),
        organization == null ? null : organization.longValue(), store == null ? null : store.longValue(),
        accountId, claims.get("authorizationVersion", Integer.class));
  }

  /**
   * 运营上下文 Token 不可用时的统一出口：401 + {@code SAAS_CONTEXT_INVALID}（与
   * {@code StaffController}/{@code MediaImageController} 的既有码一致），交给全局异常处理器渲染
   * {@code {code,message}}。过期/签名不符/结构损坏都是**正常客户端条件**——Redis 后台会话最长 30 天，
   * 而 {@link #sign} 签出的运营上下文 Token 只有 30 分钟有效期，所以「会话还在、上下文 Token 已过期」
   * 在真实使用中必然出现；它们绝不能冒泡成 500 并污染错误告警。文案固定，不回显 token 及其 claims。
   */
  private static ApiException contextUnavailable(String message) {
    return new ApiException(HttpStatusCodes.UNAUTHORIZED, CONTEXT_INVALID_CODE, message);
  }

  /** 验签并取 payload：过期回「已过期」，其余（签名/受众/结构不符）回「无效」。 */
  private Claims parseContextClaims(String token) {
    try {
      return Jwts.parser().verifyWith(secretKey).requireIssuer("open-saas-identity")
          .requireAudience("open-saas-services").build().parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException exception) {
      throw contextUnavailable(CONTEXT_EXPIRED_MESSAGE);
    } catch (JwtException | IllegalArgumentException exception) {
      throw contextUnavailable(CONTEXT_INVALID_MESSAGE);
    }
  }

  /** subject 必须是数字账号 ID；非数字视为上下文损坏，与签名不符同样按 401 处理。 */
  private static Long parseAccountId(String subject) {
    if (subject == null) {
      return null;
    }
    try {
      return Long.valueOf(subject);
    } catch (NumberFormatException exception) {
      return null;
    }
  }
}
