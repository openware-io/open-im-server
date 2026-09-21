package com.gvchat.im.user.domain.openplatform.port;

/** 应用密钥哈希端口：注册与校验时只经哈希比对，禁止明文落库。 */
public interface AppSecretHasher {
  String hash(String secret);

  boolean matches(String rawSecret, String hashedSecret);
}
