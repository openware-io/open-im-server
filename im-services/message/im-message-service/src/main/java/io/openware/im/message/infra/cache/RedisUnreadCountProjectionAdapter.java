package io.openware.im.message.infra.cache;

import io.openware.common.constant.RedisKeys;
import io.openware.im.message.domain.message.port.UnreadCountProjectionPort;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisUnreadCountProjectionAdapter implements UnreadCountProjectionPort {
  private final StringRedisTemplate redisTemplate;

  @Override
  public OptionalLong find(long userId) {
    String value = redisTemplate.opsForValue().get(unreadKey(userId));
    if (value == null) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(value));
    } catch (NumberFormatException ex) {
      invalidate(userId);
      return OptionalLong.empty();
    }
  }

  @Override
  public void replace(long userId, long count) {
    redisTemplate.opsForValue().set(unreadKey(userId), Long.toString(count));
  }

  @Override
  public void invalidate(long userId) {
    redisTemplate.delete(unreadKey(userId));
  }

  private String unreadKey(long userId) {
    return RedisKeys.UNREAD_COUNT + userId;
  }
}
