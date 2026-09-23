package io.openware.im.user.infra.security;

import io.openware.infrastructure.security.AuthenticationUserProjection;
import io.openware.im.user.application.websocket.WebSocketTicketAuthenticationPort;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class RedisWebSocketTicketAuthenticationAdapter implements WebSocketTicketAuthenticationPort {
  private final AuthenticationUserProjection authenticationUserProjection;

  @Override
  public OptionalLong activeAuthenticationVersion(long userId) {
    return authenticationUserProjection.findByUserId(userId)
        .filter(value -> value.active())
        .map(value -> OptionalLong.of(value.authenticationVersion()))
        .orElseGet(OptionalLong::empty);
  }
}
