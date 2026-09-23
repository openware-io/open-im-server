package io.openware.infrastructure.security;

import io.openware.common.port.TokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT TokenProvider 实现：负责签发与解析 Access Token。
 *
 * <p>实现要点：
 * <ul>
 *   <li>使用 HMAC-SHA 对 Token 签名，密钥来自 {@link JwtProperties#getSecret()}。</li>
 *   <li>subject 保存 userId，并额外写入 {@code username} claim。</li>
 * </ul>
 */
@Component
public class JwtTokenProvider implements TokenProvider {
  private final JwtProperties jwtProperties;
  private final SecretKey secretKey;

  /**
   * 构造 TokenProvider，并将配置中的密钥转换为 HMAC SecretKey。
   *
   * @param jwtProperties JWT 配置属性
   */
  public JwtTokenProvider(JwtProperties jwtProperties) {
    this.jwtProperties = jwtProperties;
    jwtProperties.validate();
    this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 签发 Access Token。
   *
   * @param userId   用户 ID（写入 JWT subject）
   * @param username 用户名（写入 {@code username} claim）
   * @return JWT 字符串
   */
  @Override
  public String createAccessToken(long userId, String username, long authenticationVersion) {
    return createAccessToken(userId, username, authenticationVersion, Duration.ofMillis(jwtProperties.getExpirationMs()));
  }

  @Override
  public String createAccessToken(long userId, String username, long authenticationVersion, Duration ttl) {
    Date now = new Date();
    long boundedMillis = Math.max(1000L, Math.min(ttl.toMillis(), jwtProperties.getExpirationMs()));
    Date expiry = new Date(now.getTime() + boundedMillis);
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("username", username)
        .claim("authentication_version", authenticationVersion)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey)
        .compact();
  }

  /**
   * 解析 JWT 并返回 Claims。
   *
   * @param token JWT 字符串
   * @return Claims
   * @throws io.jsonwebtoken.JwtException Token 无效时抛出
   */
  public Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(secretKey).build()
        .parseSignedClaims(token).getPayload();
  }
}
