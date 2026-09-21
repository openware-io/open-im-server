package com.gvchat.infrastructure.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * 内部服务鉴权（鉴权版本 2）：canonical request + HMAC-SHA256。
 *
 * <p>签名与内容哈希算法收敛到 {@link InternalServiceSignature}，出站客户端（含审计上报）与服务端校验
 * 共用同一实现，避免两侧口径漂移导致的 401。
 */
@Component
public class InternalServiceAuthentication {
  public static final String SOURCE_HEADER = "X-IM-Service-Source";
  public static final String VERSION_HEADER = "X-IM-Service-Auth-Version";
  public static final String REQUEST_ID_HEADER = "X-IM-Service-Request-Id";
  public static final String CONTENT_SHA256_HEADER = "X-IM-Service-Content-SHA256";
  public static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
  public static final String SIGNATURE_HEADER = "X-IM-Service-Signature";
  public static final String AUTHENTICATION_VERSION = "2";

  private final InternalServiceAuthenticationProperties properties;

  public InternalServiceAuthentication(InternalServiceAuthenticationProperties properties) {
    this.properties = properties;
    properties.validate();
  }

  public String createSignature(HttpMethod method, URI uri, String contentType, byte[] body, String source,
      String requestId, long timestamp) {
    return InternalServiceSignature.sign(properties.getSecret(), method.name(), uri.getRawPath(),
        uri.getRawQuery(), contentType, InternalServiceSignature.contentHash(body), source, requestId, timestamp);
  }

  public boolean isValid(String method, String path, String query, String contentType, byte[] body, String source,
      String requestId, String timestamp, String contentHash, String signature) {
    long timestampValue;
    try {
      timestampValue = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      return false;
    }
    if (Math.abs(System.currentTimeMillis() - timestampValue) > properties.getMaxClockSkewMs()) {
      return false;
    }
    if (!isValidRequestId(requestId) || !isValidHash(contentHash) || !isValidHash(signature)
        || !MessageDigest.isEqual(
            InternalServiceSignature.contentHash(body).getBytes(StandardCharsets.US_ASCII),
            contentHash.getBytes(StandardCharsets.US_ASCII))) {
      return false;
    }
    String expected = InternalServiceSignature.sign(properties.getSecret(), method, path, query, contentType,
        contentHash, source, requestId, timestampValue);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
  }

  public String contentHash(byte[] body) {
    return InternalServiceSignature.contentHash(body);
  }

  public String getServiceName() {
    return properties.getServiceName();
  }

  public String getExpectedSource() {
    return properties.getExpectedSource();
  }

  public long getRequestIdTtlMs() {
    return properties.getRequestIdTtlMs();
  }

  private boolean isValidRequestId(String requestId) {
    return requestId != null && requestId.matches("[A-Za-z0-9_-]{22,128}");
  }

  private boolean isValidHash(String contentHash) {
    return contentHash != null && contentHash.matches("[0-9a-f]{64}");
  }
}
