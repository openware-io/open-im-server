package com.gvchat.im.user.application.websocket;

import com.gvchat.common.constant.RedisKeys;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketTicketService {
  private static final Duration TTL = Duration.ofSeconds(60);

  private final StringRedisTemplate redisTemplate;
  private final WebSocketTicketAuthenticationPort authenticationPort;

  public IssuedTicket issue(long userId) {
    long authenticationVersion = authenticationPort.activeAuthenticationVersion(userId)
        .stream().findFirst()
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Authentication is no longer valid"));
    String ticket = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(
        RedisKeys.WS_TICKET + ticket,
        userId + "|" + authenticationVersion,
        TTL);
    return new IssuedTicket(ticket, Instant.now().plus(TTL));
  }

  public record IssuedTicket(String ticket, Instant expiresAt) { }
}
