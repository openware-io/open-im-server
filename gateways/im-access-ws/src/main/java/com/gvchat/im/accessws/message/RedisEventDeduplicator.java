package com.gvchat.im.accessws.message;

import com.gvchat.common.constant.AppConstants;
import com.gvchat.common.constant.RedisKeys;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisEventDeduplicator implements EventDeduplicator {
  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean firstDelivery(String eventId) {
    Boolean inserted = redisTemplate.opsForValue().setIfAbsent(
        RedisKeys.EVENT_DEDUP + eventId,
        "1",
        Duration.ofSeconds(AppConstants.MSG_DEDUP_TTL_SEC));
    return Boolean.TRUE.equals(inserted);
  }

  @Override
  public void release(String eventId) {
    redisTemplate.delete(RedisKeys.EVENT_DEDUP + eventId);
  }
}
