package io.openware.im.accessws.config;

import io.openware.infrastructure.security.JwtProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GatewaySecurityConfigurationValidator {
  private final JwtProperties jwtProperties;
  private final String redisPassword;

  public GatewaySecurityConfigurationValidator(
      JwtProperties jwtProperties,
      @Value("${spring.data.redis.password:}") String redisPassword) {
    this.jwtProperties = jwtProperties;
    this.redisPassword = redisPassword;
  }

  @PostConstruct
  void validate() {
    if (!StringUtils.hasText(redisPassword)) {
      throw new IllegalStateException("必须配置 REDIS_PASSWORD");
    }
    if (!StringUtils.hasText(jwtProperties.getSecret()) || jwtProperties.getSecret().length() < 32) {
      throw new IllegalStateException("JWT_SECRET 必须至少包含 32 个字符");
    }
  }
}
