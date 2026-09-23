package io.openware.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "internal.service-auth")
public class InternalServiceAuthenticationProperties {
  private static final int MINIMUM_SECRET_LENGTH = 32;

  private String serviceName;
  private String expectedSource;
  private String secret;
  private long maxClockSkewMs = 60000L;

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public String getExpectedSource() {
    return expectedSource;
  }

  public void setExpectedSource(String expectedSource) {
    this.expectedSource = expectedSource;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret(String secret) {
    this.secret = secret;
  }

  public long getMaxClockSkewMs() {
    return maxClockSkewMs;
  }

  public void setMaxClockSkewMs(long maxClockSkewMs) {
    this.maxClockSkewMs = maxClockSkewMs;
  }

  public long getRequestIdTtlMs() {
    return maxClockSkewMs * 2;
  }

  /**
   * 显式校验（由 {@link InternalServiceAuthentication} 构造器调用），而非实现
   * {@code InitializingBean}：作为 {@code @ConfigurationProperties} bean，过早实例化时
   * 绑定尚未完成，{@code afterPropertiesSet} 会误报「service names must be configured」。
   */
  public void validate() {
    if (isBlank(serviceName) || isBlank(expectedSource)) {
      throw new IllegalStateException("internal.service-auth service names must be configured");
    }
    if (isBlank(secret) || secret.length() < MINIMUM_SECRET_LENGTH) {
      throw new IllegalStateException("internal.service-auth secret must contain at least 32 characters");
    }
    if (maxClockSkewMs <= 0) {
      throw new IllegalStateException("internal.service-auth max-clock-skew-ms must be positive");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
