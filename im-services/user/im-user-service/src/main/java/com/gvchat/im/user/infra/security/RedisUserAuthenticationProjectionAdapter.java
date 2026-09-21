package com.gvchat.im.user.infra.security;

import com.gvchat.common.enums.UserRole;
import com.gvchat.infrastructure.security.AuthenticationUserProjection;
import com.gvchat.infrastructure.security.AuthenticationUserSnapshot;
import com.gvchat.im.user.domain.account.model.UserAuthenticationSnapshot;
import com.gvchat.im.user.domain.account.port.UserAuthenticationProjectionPort;
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
