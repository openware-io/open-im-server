package io.openware.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge rate-limit configuration applied per client address at the API gateway.
 *
 * <p>Read (GET/HEAD) and write (POST/PUT/PATCH/DELETE) traffic use independent
 * token buckets so that the two traffic classes can be throttled separately.
 */
@ConfigurationProperties("im.gateway.rate-limit")
public class RateLimitProperties {
  /** Whether rate limiting is active. When false every request passes through. */
  private boolean enabled = true;

  /** Sustained read traffic allowance, in requests per minute. */
  private int readRequestsPerMinute = 120;

  /** Sustained write traffic allowance, in requests per minute. */
  private int writeRequestsPerMinute = 60;

  /** Redis key prefix shared by all rate-limit buckets. */
  private String keyPrefix = "rl:gateway";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getReadRequestsPerMinute() {
    return readRequestsPerMinute;
  }

  public void setReadRequestsPerMinute(int readRequestsPerMinute) {
    this.readRequestsPerMinute = readRequestsPerMinute;
  }

  public int getWriteRequestsPerMinute() {
    return writeRequestsPerMinute;
  }

  public void setWriteRequestsPerMinute(int writeRequestsPerMinute) {
    this.writeRequestsPerMinute = writeRequestsPerMinute;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }
}
