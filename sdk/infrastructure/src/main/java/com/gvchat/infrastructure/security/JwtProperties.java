package com.gvchat.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置属性，绑定 {@code jwt.*} 配置项。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
  /** HMAC 签名密钥 */
  private String secret;
  /** Access Token 有效期（毫秒），默认 7 */
  private long expirationMs = 604800000L;

  public void validate() {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT_SECRET 未配置，服务拒绝启动");
    }
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("JWT_SECRET 长度必须不少于 32 字节");
    }
    if (Set.of("default-secret-change-in-production", "change-me", "secret", "jwt-secret").contains(secret)) {
      throw new IllegalStateException("JWT_SECRET 使用了禁止的弱密钥");
    }
  }
}
