package com.gvchat.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内部服务 HMAC 签名共享实现（鉴权版本 2 的 canonical request）。
 *
 * <p>历史上出站客户端各自实现「sha256(service:timestamp:secret)」占位签名，与服务端
 * {@link InternalServiceAuthentication} 的 HMAC canonical request 口径不一致，内部接口一旦开启强校验
 * 就会被 401 拒绝。这里把算法收敛到一处：服务端校验与所有出站调用（含审计上报）共用同一实现，
 * 签名口径不会再次漂移。
 */
public final class InternalServiceSignature {

  private InternalServiceSignature() {}

  /** 请求体 SHA-256（十六进制小写）；空体按空字节数组计算。 */
  public static String contentHash(byte[] body) {
    try {
      return toHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to create internal service content hash", e);
    }
  }

  /** 生成 HMAC-SHA256 签名（十六进制小写）。 */
  public static String sign(String secret, String method, String path, String query, String contentType,
      String contentHash, String source, String requestId, long timestamp) {
    return hmac(secret, canonical(method, path, query, contentType, contentHash, source, requestId, timestamp));
  }

  /** 鉴权版本 2 的 canonical request 字符串，字段顺序与分隔符不可变更。 */
  public static String canonical(String method, String path, String query, String contentType, String contentHash,
      String source, String requestId, long timestamp) {
    return InternalServiceAuthentication.AUTHENTICATION_VERSION + "\n" + method + "\n" + path + "\n"
        + (query == null ? "" : query) + "\n" + (contentType == null ? "" : contentType) + "\n" + contentHash + "\n"
        + source + "\n" + requestId + "\n" + timestamp;
  }

  private static String hmac(String secret, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to create internal service signature", e);
    }
  }

  private static String toHex(byte[] bytes) {
    char[] hex = "0123456789abcdef".toCharArray();
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      int unsignedValue = Byte.toUnsignedInt(value);
      builder.append(hex[unsignedValue >>> 4]);
      builder.append(hex[unsignedValue & 0x0f]);
    }
    return builder.toString();
  }
}
