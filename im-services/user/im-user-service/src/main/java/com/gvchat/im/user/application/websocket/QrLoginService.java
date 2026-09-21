package com.gvchat.im.user.application.websocket;

import com.gvchat.common.constant.RedisKeys;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 扫码登录会话管理：Redis 存 pending / confirmed:{userId}，确认后一次性消费。 */
@Service
@RequiredArgsConstructor
public class QrLoginService {
  private static final Duration SESSION_TTL = Duration.ofSeconds(120);
  private static final Duration CONFIRMED_TTL = Duration.ofSeconds(30);

  private final StringRedisTemplate redisTemplate;

  public String createSession() {
    String qrToken = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(RedisKeys.QR_LOGIN + qrToken, "pending", SESSION_TTL);
    return qrToken;
  }

  public boolean confirm(String qrToken, long userId) {
    String key = RedisKeys.QR_LOGIN + qrToken;
    String value = redisTemplate.opsForValue().get(key);
    if (value == null) {
      return false;
    }
    redisTemplate.opsForValue().set(key, "confirmed:" + userId, CONFIRMED_TTL);
    return true;
  }

  public QrLoginState poll(String qrToken) {
    String key = RedisKeys.QR_LOGIN + qrToken;
    String value = redisTemplate.opsForValue().get(key);
    if (value == null) {
      return QrLoginState.expired();
    }
    if ("pending".equals(value)) {
      return QrLoginState.pending();
    }
    if (value.startsWith("confirmed:")) {
      long userId = Long.parseLong(value.substring("confirmed:".length()));
      redisTemplate.delete(key);
      return QrLoginState.confirmed(userId);
    }
    return QrLoginState.expired();
  }

  public record QrLoginState(String status, Long userId) {
    public static QrLoginState pending() {
      return new QrLoginState("pending", null);
    }

    public static QrLoginState expired() {
      return new QrLoginState("expired", null);
    }

    public static QrLoginState confirmed(long userId) {
      return new QrLoginState("confirmed", userId);
    }
  }
}
