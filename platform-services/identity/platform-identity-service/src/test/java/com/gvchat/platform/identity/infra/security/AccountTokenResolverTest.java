package com.gvchat.platform.identity.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 登录 Access Token 解析：仅接受合法签名 JWT，拒绝缺失/非 Bearer/非法。 */
class AccountTokenResolverTest {

  private JwtTokenProvider jwtTokenProvider;
  private AccountTokenResolver resolver;

  @BeforeEach
  void setUp() {
    JwtProperties props = new JwtProperties();
    props.setSecret("0123456789abcdef0123456789abcdef0123456789");
    props.setExpirationMs(60000L);
    jwtTokenProvider = new JwtTokenProvider(props);
    resolver = new AccountTokenResolver(jwtTokenProvider);
  }

  @Test
  void resolveAccountId_returnsSubjectFromValidToken() {
    String token = jwtTokenProvider.createAccessToken(42L, "13800000001", 0);

    assertEquals(42L, resolver.resolveAccountId("Bearer " + token));
  }

  @Test
  void resolveAccountId_throwsWhenHeaderMissing() {
    ApiException ex = assertThrows(ApiException.class, () -> resolver.resolveAccountId(null));
    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_TOKEN_REQUIRED", ex.getCode());
  }

  @Test
  void resolveAccountId_throwsWhenNotBearer() {
    ApiException ex = assertThrows(ApiException.class, () -> resolver.resolveAccountId("Basic abc123"));
    assertEquals(401, ex.getStatus());
  }

  @Test
  void resolveAccountId_throwsWhenTokenInvalid() {
    ApiException ex = assertThrows(ApiException.class, () -> resolver.resolveAccountId("Bearer not-a-jwt"));
    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_TOKEN_INVALID", ex.getCode());
  }
}
