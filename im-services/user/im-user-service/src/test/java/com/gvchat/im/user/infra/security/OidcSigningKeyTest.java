package com.gvchat.im.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** D7：OIDC 签名密钥治理（注入 PEM/固定 kid/缺失即失败；开发回退）。 */
class OidcSigningKeyTest {

  private String toPkcs8Pem(KeyPair pair) {
    String body = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
  }

  private KeyPair rsa() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  @Test
  void defaultDevFallback_stillSignsAndJwksMatches() throws Exception {
    OidcTokenSigner signer = new OidcTokenSigner("https://issuer.test");
    String token = signer.signIdToken("sub-1", "client-1", "nonce-1", "openid profile.basic", 42L);
    RSAPublicKey pub = signer.publicKey();
    Jwts.parser().verifyWith(pub).build().parseSignedClaims(token);
    assertThat(signer.jwk()).containsEntry("kid", signer.keyId());
  }

  @Test
  void injectedKey_usesConfiguredKid_andVerifies() throws Exception {
    KeyPair pair = rsa();
    String pem = toPkcs8Pem(pair);
    OidcTokenSigner signer = new OidcTokenSigner("https://issuer.test", pem, null, "ack-oidc-1", true);
    assertThat(signer.keyId()).isEqualTo("ack-oidc-1");
    assertThat(signer.publicKey().getModulus())
        .isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
    String token = signer.signIdToken("sub-1", "client-1", "nonce-1", "openid", 7L);
    Jwts.parser().verifyWith(signer.publicKey()).build().parseSignedClaims(token);
    // 同 PEM + 同 kid 重建后 kid 不变（多副本一致性的前提）
    OidcTokenSigner second = new OidcTokenSigner("https://issuer.test", pem, null, "ack-oidc-1", true);
    assertThat(second.keyId()).isEqualTo(signer.keyId());
    assertThat(second.jwk().get("n")).isEqualTo(signer.jwk().get("n"));
  }

  @Test
  void configuredKidAbsent_derivesStableKidFromKey() throws Exception {
    KeyPair pair = rsa();
    String pem = toPkcs8Pem(pair);
    OidcTokenSigner first = new OidcTokenSigner("https://issuer.test", pem, null, "", true);
    OidcTokenSigner second = new OidcTokenSigner("https://issuer.test", pem, null, null, true);
    assertThat(first.keyId()).isEqualTo(second.keyId());
    assertThat(first.keyId()).startsWith("im-oidc-");
  }

  @Test
  void requireConfiguredWithoutKey_failsFast() {
    assertThatThrownBy(() -> new OidcTokenSigner("https://issuer.test", null, null, null, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("signing key is required");
  }

  @Test
  void keyFilePathIsSupported() throws Exception {
    KeyPair pair = rsa();
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("oidc-key-", ".pem");
    java.nio.file.Files.writeString(tmp, toPkcs8Pem(pair));
    try {
      OidcTokenSigner signer = new OidcTokenSigner("https://issuer.test", null, tmp.toString(), "file-kid", true);
      assertThat(signer.keyId()).isEqualTo("file-kid");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }
}
