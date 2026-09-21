package com.gvchat.im.user.api.controller;

import com.gvchat.im.user.domain.openplatform.port.OidcTokenSigner;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** OpenID Connect discovery and signing key endpoints. */
@RestController
@RequiredArgsConstructor
public class OidcMetadataController {
  private final OidcTokenSigner signer;

  @GetMapping("/.well-known/openid-configuration")
  public Map<String, Object> configuration() {
    String issuer = signer.issuer();
    return Map.ofEntries(
        Map.entry("issuer", issuer),
        Map.entry("authorization_endpoint", issuer + "/oauth/authorize"),
        Map.entry("token_endpoint", issuer + "/oauth/token"),
        Map.entry("userinfo_endpoint", issuer + "/oauth/userinfo"),
        Map.entry("jwks_uri", issuer + "/oauth/jwks"),
        Map.entry("introspection_endpoint", issuer + "/oauth/introspect"),
        Map.entry("revocation_endpoint", issuer + "/oauth/revoke"),
        Map.entry("response_types_supported", List.of("code")),
        Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
        Map.entry("subject_types_supported", List.of("pairwise")),
        Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
        Map.entry("scopes_supported", List.of("openid", "profile.basic", "profile.phone")),
        Map.entry("token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic")),
        Map.entry("code_challenge_methods_supported", List.of("S256")));
  }

  @GetMapping("/oauth/jwks")
  public Map<String, Object> jwks() {
    return Map.of("keys", List.of(signer.jwk()));
  }
}
