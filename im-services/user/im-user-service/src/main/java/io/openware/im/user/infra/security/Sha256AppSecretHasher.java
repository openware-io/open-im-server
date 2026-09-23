package io.openware.im.user.infra.security;

import io.openware.im.user.domain.openplatform.port.AppSecretHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 应用密钥 SHA-256 哈希实现：注册与校验均走哈希比对，禁止明文落库。 */
@Component
public class Sha256AppSecretHasher implements AppSecretHasher {
  @Override
  public String hash(String secret) {
    return HexFormat.of().formatHex(sha256(secret));
  }

  @Override
  public boolean matches(String rawSecret, String hashedSecret) {
    if (rawSecret == null || hashedSecret == null) {
      return false;
    }
    String computed = hash(rawSecret);
    return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
        hashedSecret.getBytes(StandardCharsets.US_ASCII));
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
