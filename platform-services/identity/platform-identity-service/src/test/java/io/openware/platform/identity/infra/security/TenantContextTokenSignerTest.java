package io.openware.platform.identity.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 经营上下文 Token 签名器：30 分钟 JWT，payload 含 tenantId/organizationId/storeId/authorizationVersion。 */
class TenantContextTokenSignerTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private SecretKey secretKey;
  private TenantContextTokenSigner signer;

  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    properties.validate();
    signer = new TenantContextTokenSigner(properties);
    secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void sign_issuesThirtyMinuteJwtWithScopeClaims() {
    String token = signer.sign(100L, 1L, 10L, 1001L, 7, List.of("perm.read", "perm.write"));

    Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();

    assertEquals("100", claims.getSubject());
    assertEquals("open-saas-identity", claims.getIssuer());
    assertTrue(claims.getAudience().contains("open-saas-services"));
    assertEquals(1L, ((Number) claims.get("tenantId")).longValue());
    assertEquals(10L, ((Number) claims.get("organizationId")).longValue());
    assertEquals(1001L, ((Number) claims.get("storeId")).longValue());
    assertEquals(7, ((Number) claims.get("authorizationVersion")).intValue());
    assertEquals(List.of("perm.read", "perm.write"), claims.get("permissions", List.class));

    long issuedAt = claims.getIssuedAt().getTime() / 1000;
    long expiresAt = claims.getExpiration().getTime() / 1000;
    long ttlSeconds = expiresAt - issuedAt;
    assertTrue(ttlSeconds >= 1799 && ttlSeconds <= 1801,
        "token TTL should be ~30 minutes, was " + ttlSeconds);
    // 不传币种的老口径必须签出 USD（规范 §3.1：缺省 USD，不得留空 claim）
    assertEquals("USD", claims.get("currency", String.class));
  }

  /** 配置为 CNY 的租户：claim currency = CNY。 */
  @Test
  void sign_writesConfiguredCurrencyClaim() {
    String token = signer.sign(100L, 1L, 10L, 1001L, 7, List.of("perm.read"), "CNY");

    Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();

    assertEquals("CNY", claims.get("currency", String.class));
  }

  /** 未配置（null/空/非法）时 claim 落 USD，绝不写入未知值。 */
  @Test
  void sign_fallsBackToUsdForMissingOrUnknownCurrency() {
    String nullToken = signer.sign(100L, 1L, 10L, 1001L, 7, List.of("perm.read"), null);
    assertEquals("USD", Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(nullToken)
        .getPayload().get("currency", String.class));

    String unknownToken = signer.sign(100L, 1L, 10L, 1001L, 7, List.of("perm.read"), "RMB");
    assertEquals("USD", Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(unknownToken)
        .getPayload().get("currency", String.class));
  }
}
