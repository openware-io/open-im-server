package io.openware.gateway.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Wires the edge rate limiter: a token-bucket Lua script plus a global filter
 * that enforces it per client address over the {@code /api/**} surface.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

  private static final String RATE_LIMIT_LUA =
      """
      local tokens_key = KEYS[1]
      local timestamp_key = KEYS[2]
      local rate = tonumber(ARGV[1])
      local capacity = tonumber(ARGV[2])
      local now = tonumber(ARGV[3])
      local requested = tonumber(ARGV[4])
      local fill_time = capacity / rate
      local ttl = math.floor(fill_time * 2) + 1
      local last_tokens = tonumber(redis.call('get', tokens_key))
      if last_tokens == nil then
        last_tokens = capacity
      end
      local last_refreshed = tonumber(redis.call('get', timestamp_key))
      if last_refreshed == nil then
        last_refreshed = 0
      end
      local delta = math.max(0, now - last_refreshed)
      local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
      local allowed = filled_tokens >= requested
      local new_tokens = filled_tokens
      local allowed_num = 0
      if allowed then
        new_tokens = filled_tokens - requested
        allowed_num = 1
        redis.call('setex', timestamp_key, ttl, now)
      end
      redis.call('setex', tokens_key, ttl, new_tokens)
      return { allowed_num, new_tokens }
      """;

  @Bean
  @SuppressWarnings({"rawtypes", "unchecked"})
  public DefaultRedisScript<List<Long>> rateLimitScript() {
    DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
    script.setScriptText(RATE_LIMIT_LUA);
    script.setResultType((Class) List.class);
    return script;
  }

  @Bean
  public RateLimitFilter rateLimitFilter(
      RateLimitProperties properties,
      ReactiveStringRedisTemplate redisTemplate,
      DefaultRedisScript<List<Long>> rateLimitScript) {
    return new RateLimitFilter(
        properties,
        redisTemplate,
        rateLimitScript,
        XForwardedRemoteAddressResolver.maxTrustedIndex(1));
  }
}
