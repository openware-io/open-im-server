package com.gvchat.im.user.infra.security;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs OIDC ID tokens and exposes the public signing material for JWKS.
 *
 * <p>D7（签名密钥治理）：支持从配置注入 RSA 私钥（PEM 内联或 Secret 文件路径）并固定/稳定化 kid；
 * 多副本/重启后 JWKS 与签名保持一致。未配置密钥时：若 {@code oidc.signing.require-configured=false}
 * （开发默认）则临时生成并打 WARN 日志回退；若为 true（生产/ACK）则启动抛错，避免“无密钥仍运行”。
 */
@Component
public class OidcTokenSigner implements com.gvchat.im.user.domain.openplatform.port.OidcTokenSigner {
  private final String issuer;
  private final AtomicReference<KeyPair> keyPair = new AtomicReference<>();
  private final String kid;

  /** 测试/兼容构造：始终开发回退（临时生成密钥）。 */
  public OidcTokenSigner(String issuer) {
    this(issuer, null, null, null, false);
  }

  @Autowired
  public OidcTokenSigner(
      @Value("${oidc.issuer:http://localhost:3100}") String issuer,
      @Value("${oidc.signing.private-key-pem:}") String privateKeyPem,
      @Value("${oidc.signing.private-key-path:}") String privateKeyPath,
      @Value("${oidc.signing.kid:}") String kidOverride,
      @Value("${oidc.signing.require-configured:false}") boolean requireConfigured) {
    this.issuer = issuer;
    this.keyPair.set(loadKeyPair(privateKeyPem, privateKeyPath, requireConfigured));
    String derived = "im-oidc-" + HexFormat.of().formatHex(fingerprint(publicKey()));
    this.kid = (kidOverride == null || kidOverride.isBlank()) ? derived : kidOverride.trim();
  }

  public String issuer() {
    return issuer;
  }

  public String keyId() {
    return kid;
  }

  public RSAPublicKey publicKey() {
    return (RSAPublicKey) keyPair.get().getPublic();
  }

  public Map<String, Object> jwk() {
    RSAPublicKey key = publicKey();
    return Map.of("kty", "RSA", "use", "sig", "alg", "RS256", "kid", kid,
        "n", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(key.getModulus().toByteArray())),
        "e", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(key.getPublicExponent().toByteArray())));
  }

  public String signIdToken(String subject, String clientId, String nonce, String scope, long userId) {
    Instant issuedAt = Instant.now();
    var builder = Jwts.builder().header().keyId(kid).and()
        .issuer(issuer).subject(subject).audience().add(clientId).and()
        .issuedAt(Date.from(issuedAt)).expiration(Date.from(issuedAt.plusSeconds(300)))
        .claim("azp", clientId).claim("scope", scope).claim("uid", userId);
    if (nonce != null && !nonce.isBlank()) {
      builder.claim("nonce", nonce);
    }
    return builder.signWith(keyPair.get().getPrivate(), Jwts.SIG.RS256).compact();
  }

  private KeyPair loadKeyPair(String privateKeyPem, String privateKeyPath, boolean requireConfigured) {
    String pem = null;
    if (privateKeyPath != null && !privateKeyPath.isBlank()) {
      try {
        pem = Files.readString(Path.of(privateKeyPath.trim()), StandardCharsets.UTF_8);
      } catch (Exception ex) {
        throw new IllegalStateException("Unable to read OIDC signing key file: " + privateKeyPath, ex);
      }
    } else if (privateKeyPem != null && !privateKeyPem.isBlank()) {
      pem = privateKeyPem;
    }
    if (pem == null || pem.isBlank()) {
      if (requireConfigured) {
        throw new IllegalStateException(
            "OIDC signing key is required (oidc.signing.require-configured=true) but no "
                + "oidc.signing.private-key-pem/private-key-path configured.");
      }
      return generateKeyPair();
    }
    try {
      PrivateKey privateKey = parsePrivateKey(pem);
      if (!(privateKey instanceof RSAPrivateCrtKey rsaCrt)) {
        throw new IllegalStateException("Configured OIDC signing key must be an RSA key (PKCS#8 PEM).");
      }
      RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(rsaCrt.getModulus(), rsaCrt.getPublicExponent()));
      return new KeyPair(publicKey, privateKey);
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid OIDC signing key PEM", ex);
    }
  }

  private PrivateKey parsePrivateKey(String pem) throws Exception {
    String body = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(body);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    } catch (Exception pkcs8Failure) {
      throw new IllegalStateException(
          "Private key must be PKCS#8 'BEGIN PRIVATE KEY' PEM (see docs/deployment/ACK.md 生成示例).", pkcs8Failure);
    }
  }

  private KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to initialize OIDC signing key", ex);
    }
  }

  private static byte[] fingerprint(RSAPublicKey key) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      return md.digest(key.getEncoded());
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to fingerprint OIDC public key", ex);
    }
  }

  private byte[] unsigned(byte[] value) {
    return value.length > 1 && value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value;
  }
}
