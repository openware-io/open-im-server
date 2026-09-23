package io.openware.im.user.infra.security;

import io.openware.im.user.domain.account.port.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringPasswordHasher implements PasswordHasher {
  private final PasswordEncoder passwordEncoder;

  @Override
  public String hash(String plainText) {
    return passwordEncoder.encode(plainText);
  }

  @Override
  public boolean matches(String plainText, String passwordHash) {
    return passwordEncoder.matches(plainText, passwordHash);
  }
}
