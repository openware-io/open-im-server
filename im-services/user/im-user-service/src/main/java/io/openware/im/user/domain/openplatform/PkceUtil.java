package io.openware.im.user.domain.openplatform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** PKCE S256 工具：code_challenge = base64url(sha256(code_verifier))，不带填充。 */
public final class PkceUtil {
  private static final String S256 = "S256";

  private PkceUtil() {
  }

  public static boolean isS256(String method) {
    return S256.equalsIgnoreCase(method == null ? "" : method.trim());
  }

  public static String computeChallenge(String codeVerifier) {
    byte[] digest = sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  public static boolean verify(String codeVerifier, String expectedChallenge) {
    if (codeVerifier == null || expectedChallenge == null) {
      return false;
    }
    byte[] computed = computeChallenge(codeVerifier).getBytes(StandardCharsets.US_ASCII);
    byte[] expected = expectedChallenge.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(computed, expected);
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
