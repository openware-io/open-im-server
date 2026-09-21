package com.gvchat.platform.admin.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextFilter;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 平台作用域上下文 token 的往返回归（② 遗留）：
 * 后台服务签发的 {@code tenantId=0 + scopeType=PLATFORM} token 必须能被 SDK 的租户上下文过滤器验签接受，
 * 从而让平台级路由（{@code /api/v1/admin/platform/**}）在下游拿到操作人；
 * 且未声明平台作用域的零租户 token 一律 401（不放松签名与租户边界校验）。
 */
class AdminTenantContextTokenSignerTest {

  private static final String SECRET = "saas-admin-platform-context-test-secret-0123456789";

  private final JwtProperties properties = jwtProperties();
  private final AdminTenantContextTokenSigner signer = new AdminTenantContextTokenSigner(properties);

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void platformTokenCarriesOperatorAndIsAcceptedBySdkFilter() throws Exception {
    String token = signer.signPlatform(900L, Duration.ofMinutes(5));

    AtomicReference<TenantContext> seen = new AtomicReference<>();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    new TenantContextFilter(properties).doFilter(request, response, (req, res) -> seen.set(TenantContextHolder.get()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().tenantId()).isZero();
    assertThat(seen.get().accountId()).isEqualTo(900L);
    assertThat(seen.get().platformScope()).isTrue();
  }

  /** 平台 token 不能由被篡改的 payload 复用：改一个字节即验签失败（401）。 */
  @Test
  void tamperedPlatformTokenIsRejected() throws Exception {
    String token = signer.signPlatform(900L, Duration.ofMinutes(5));
    String[] parts = token.split("\\.");
    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
        java.nio.charset.StandardCharsets.UTF_8);
    String tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        payload.replaceFirst("900", "901").getBytes(StandardCharsets.UTF_8));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, parts[0] + "." + tampered + "." + parts[2]);
    MockHttpServletResponse response = new MockHttpServletResponse();

    new TenantContextFilter(properties).doFilter(request, response, (req, res) -> { });

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /** 会话有效期合法时 token 的有效期跟随会话，不会被硬编码成 30 分钟。 */
  @Test
  void platformTokenExpiryFollowsSessionTtl() {
    String shortLived = signer.signPlatform(900L, Duration.ofSeconds(30));
    String longLived = signer.signPlatform(900L, Duration.ofHours(2));

    assertThat(expiryOf(longLived)).isGreaterThan(expiryOf(shortLived));
  }

  /** 非法时长（0/负数/null）回退 30 分钟，不签出「立刻过期」的 token。 */
  @Test
  void invalidTtlFallsBackToDefault() {
    assertThat(expiryOf(signer.signPlatform(900L, Duration.ZERO)))
        .isGreaterThan(System.currentTimeMillis());
    assertThat(expiryOf(signer.signPlatform(900L, null)))
        .isGreaterThan(System.currentTimeMillis());
  }

  @Test
  void tenantContextEnforcesMinimumSecretLength() {
    JwtProperties weak = new JwtProperties();
    weak.setSecret("too-short");
    assertThatThrownBy(() -> new AdminTenantContextTokenSigner(weak))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 有效 Token 的语义不变：照常解析出租户/账号/授权版本。 */
  @Test
  void validContextTokenStillVerifies() {
    String token = signer.sign(900L, 100L, 7L, 11L, 3, List.of("resource.read"));

    TenantContext context = signer.verify(token);

    assertThat(context.tenantId()).isEqualTo(100L);
    assertThat(context.organizationId()).isEqualTo(7L);
    assertThat(context.storeId()).isEqualTo(11L);
    assertThat(context.accountId()).isEqualTo(900L);
    assertThat(context.authorizationVersion()).isEqualTo(3);
  }

  /**
   * 会话仍有效但内嵌运营上下文 Token 已过期（会话 2 小时–30 天 vs Token 30 分钟）：
   * 必须回 401 + {@code SAAS_CONTEXT_INVALID}，而不是冒泡成 500；且消息不得回显 token。
   */
  @Test
  void expiredContextTokenIsReportedAs401() {
    String expired = expiredContextToken();

    ApiException error = catchThrowableOfType(() -> signer.verify(expired), ApiException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatusCodes.UNAUTHORIZED);
    assertThat(error.getCode()).isEqualTo("SAAS_CONTEXT_INVALID");
    assertThat(error.getMessage()).isEqualTo("运营上下文已过期，请重新登录");
    assertThat(error.getMessage()).doesNotContain(expired);
  }

  /** 畸形/被篡改/密钥不符的 Token 同样回 401（不是 500），且不回显 token。 */
  @Test
  void malformedContextTokenIsReportedAs401() {
    String wrongKey = Jwts.builder().subject("900").issuer("gv-saas-identity")
        .audience().add("gv-saas-services").and().claim("tenantId", 100L)
        .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000L))
        .signWith(Keys.hmacShaKeyFor("another-test-secret-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8)))
        .compact();

    for (String token : List.of("not-a-jwt", "a.b.c", wrongKey, "null")) {
      ApiException error = catchThrowableOfType(() -> signer.verify(token), ApiException.class);
      assertThat(error.getStatus()).as("token=%s", token).isEqualTo(HttpStatusCodes.UNAUTHORIZED);
      assertThat(error.getCode()).as("token=%s", token).isEqualTo("SAAS_CONTEXT_INVALID");
      assertThat(error.getMessage()).as("token=%s", token).doesNotContain(token);
    }
  }

  /** 空 token 同样是「无效」401，不 NPE、不 500。 */
  @Test
  void blankContextTokenIsReportedAs401() {
    for (String token : new String[] {"", "   "}) {
      ApiException error = catchThrowableOfType(() -> signer.verify(token), ApiException.class);
      assertThat(error.getStatus()).as("token='%s'", token).isEqualTo(HttpStatusCodes.UNAUTHORIZED);
      assertThat(error.getCode()).as("token='%s'", token).isEqualTo("SAAS_CONTEXT_INVALID");
    }
  }

  /** 签过名但缺 tenantId 的上下文按「无效」处理：不 NPE、不 500。 */
  @Test
  void contextTokenWithoutTenantIdIsReportedAs401() {
    String withoutTenant = Jwts.builder().subject("900").issuer("gv-saas-identity")
        .audience().add("gv-saas-services").and()
        .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000L))
        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

    ApiException error = catchThrowableOfType(() -> signer.verify(withoutTenant), ApiException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatusCodes.UNAUTHORIZED);
    assertThat(error.getCode()).isEqualTo("SAAS_CONTEXT_INVALID");
  }

  private static long expiryOf(String token) {
    return Jwts.parser()
        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
        .build().parseSignedClaims(token).getPayload().getExpiration().getTime();
  }

  /** 用同一密钥签出一个已过期的运营上下文 Token（过期 30 分钟）。 */
  private static String expiredContextToken() {
    long now = System.currentTimeMillis();
    return Jwts.builder().subject("900").issuer("gv-saas-identity")
        .audience().add("gv-saas-services").and().claim("tenantId", 100L)
        .claim("authorizationVersion", 1)
        .issuedAt(new Date(now - 3_600_000L)).expiration(new Date(now - 1_800_000L))
        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
  }

  private static JwtProperties jwtProperties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    return properties;
  }
}
