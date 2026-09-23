package io.openware.im.user.application.openplatform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.im.user.api.controller.OidcMetadataController;
import io.openware.im.user.infra.security.OidcTokenSigner;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Contract checks for the public OIDC discovery and token signing surface. */
class OidcProviderContractTest {
  @Test
  void discoveryPublishesStandardEndpointsAndMethods() {
    Map<String, Object> document = new OidcMetadataController(new OidcTokenSigner("http://issuer.test"))
        .configuration();

    assertEquals("http://issuer.test", document.get("issuer"));
    assertEquals("http://issuer.test/oauth/token", document.get("token_endpoint"));
    assertTrue(((java.util.List<?>) document.get("response_types_supported")).contains("code"));
    assertTrue(((java.util.List<?>) document.get("code_challenge_methods_supported")).contains("S256"));
  }

  @Test
  void idTokenContainsOidcClaimsAndKeyId() {
    OidcTokenSigner signer = new OidcTokenSigner("http://issuer.test");
    String token = signer.signIdToken("sub-1", "client-1", "nonce-1", "openid profile.basic", 7L);

    String[] parts = token.split("\\.");
    assertEquals(3, parts.length);
    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"iss\":\"http://issuer.test\""));
    assertTrue(payload.contains("\"aud\":[\"client-1\"]"));
    assertTrue(payload.contains("\"nonce\":\"nonce-1\""));
    String header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(header.contains(signer.keyId()));
  }
}
