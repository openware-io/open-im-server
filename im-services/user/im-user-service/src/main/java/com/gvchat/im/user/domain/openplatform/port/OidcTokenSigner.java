package com.gvchat.im.user.domain.openplatform.port;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/** Port for signing OIDC ID tokens without leaking crypto infrastructure into application code. */
public interface OidcTokenSigner {
  String issuer();

  String signIdToken(String subject, String clientId, String nonce, String scope, long userId);

  String keyId();

  RSAPublicKey publicKey();

  Map<String, Object> jwk();
}
