package io.openware.im.message.infra.cache;

import io.openware.common.constant.RedisKeys;
import io.openware.im.message.domain.message.port.AdminMessageTotalCountPort;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 管理端消息总数缓存：分页 COUNT(*) 在大表上开销明显，缓存 60 秒可接受近似总数。 */
@Component
@RequiredArgsConstructor
public class AdminMessageTotalCountCache implements AdminMessageTotalCountPort {
  private static final Duration TTL = Duration.ofSeconds(60);

  private final StringRedisTemplate redisTemplate;

  /** 返回缓存的未过期总数；未命中返回 null。 */
  public Long get() {
    String value = redisTemplate.opsForValue().get(RedisKeys.ADMIN_MESSAGE_TOTAL);
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      redisTemplate.delete(RedisKeys.ADMIN_MESSAGE_TOTAL);
      return null;
    }
  }

  public void put(long total) {
    redisTemplate.opsForValue().set(RedisKeys.ADMIN_MESSAGE_TOTAL, Long.toString(total), TTL);
  }
}
