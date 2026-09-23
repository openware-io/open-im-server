package io.openware.im.user.infra.security;

import io.openware.common.enums.UserRole;
import io.openware.infrastructure.security.AuthenticationUserProjection;
import io.openware.infrastructure.security.AuthenticationUserSnapshot;
import io.openware.im.user.domain.account.model.UserAuthenticationSnapshot;
import io.openware.im.user.domain.account.port.UserAuthenticationProjectionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisUserAuthenticationProjectionAdapter implements UserAuthenticationProjectionPort {
  private final AuthenticationUserProjection authenticationUserProjection;

  @Override
  public void save(UserAuthenticationSnapshot snapshot) {
    authenticationUserProjection.save(new AuthenticationUserSnapshot(
        snapshot.userId(),
        snapshot.username(),
        UserRole.valueOf(snapshot.role().name()),
        snapshot.active(),
        snapshot.authenticationVersion()));
  }
}
