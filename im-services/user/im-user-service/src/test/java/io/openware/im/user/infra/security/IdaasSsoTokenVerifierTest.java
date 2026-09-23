package io.openware.im.user.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IDaaS SSO Token 校验器：共享 jwt.secret 验签 + 提取 username。 */
class IdaasSsoTokenVerifierTest {

  private static final String SECRET = "your-super-secret-key-change-in-production";

  private SecretKey secretKey;
  private IdaasSsoTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    JwtTokenProvider tokenProvider = new JwtTokenProvider(properties);
    verifier = new IdaasSsoTokenVerifier(tokenProvider);
    secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void verifyUsername_extractsUsernameFromIdaasToken() {
    // 模拟 IDaaS GroupJwtSigner.sign：subject=accountId，username claim，24h TTL。
    String token = Jwts.builder()
        .subject("1")
        .claim("username", "admin")
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L))
        .signWith(secretKey)
        .compact();

    assertEquals("admin", verifier.verifyUsername(token));
  }

  @Test
  void verifyUsername_rejectsInvalidToken() {
    ApiException ex = assertThrows(ApiException.class, () -> verifier.verifyUsername("not-a-jwt"));
    assertEquals(401, ex.getStatus());
    assertEquals("SSO_IDAAS_TOKEN_INVALID", ex.getCode());
  }
}
