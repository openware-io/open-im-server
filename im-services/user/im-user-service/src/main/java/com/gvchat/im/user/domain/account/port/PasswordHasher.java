package com.gvchat.im.user.domain.account.port;

public interface PasswordHasher {
  String hash(String plainText);

  boolean matches(String plainText, String passwordHash);
}
