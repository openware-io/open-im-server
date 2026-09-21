package com.gvchat.group.idaas.infra.security;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 集团 IDaaS 登录 Token 签名器：复用 jwt.secret 签发 24 小时 JWT，
 * payload 含 subject(accountId) 与 username。
 */
@Component
public class GroupJwtSigner {

  /** Access Token 有效期（24 小时）。 */
  private static final long TTL_MS = 24 * 60 * 60 * 1000L;

  private final SecretKey secretKey;

  public GroupJwtSigner(JwtProperties jwtProperties) {
    jwtProperties.validate();
    this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 签发 Access Token。
   *
   * @param accountId 账号ID（写入 subject）
   * @param username  用户名（写入 username claim）
   * @return token 与过期时间
   */
  public SignedToken sign(long accountId, String username) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + TTL_MS);
    String token = Jwts.builder()
        .subject(String.valueOf(accountId))
        .claim("username", username)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey)
        .compact();
    return new SignedToken(token, expiry.getTime());
  }

  /**
   * 校验 SSO Token（共享 jwt.secret），返回解析后的声明。
   *
   * @param token 待校验的 JWT
   * @return 账号ID + 用户名 + 过期时间
   */
  public VerifiedClaims verify(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(secretKey).build()
          .parseSignedClaims(token).getPayload();
      long accountId = Long.parseLong(claims.getSubject());
      String username = claims.get("username", String.class);
      return new VerifiedClaims(accountId, username, claims.getExpiration().getTime());
    } catch (Exception ex) {
      throw new ApiException(401, "AUTH_INVALID_TOKEN", "SSO 令牌无效或已过期");
    }
  }

  /** 签发结果：accessToken 与过期时间（epoch 毫秒）。 */
  public record SignedToken(String accessToken, long expiresAt) {}

  /** 校验结果：账号ID + 用户名 + 过期时间。 */
  public record VerifiedClaims(long accountId, String username, long expiresAt) {}
}
