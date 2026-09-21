package com.gvchat.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyContextHolder;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import com.gvchat.infrastructure.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantContextFilterTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private TenantContextFilter filter;

  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    filter = new TenantContextFilter(properties);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
    CurrencyContextHolder.clear();
  }

  @Test
  void acceptsSignedTenantContextAndClearsItAfterRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(1L, 10L, 1001L, 100L));
    AtomicReference<TenantContext> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(TenantContextHolder.get());

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    TenantContext context = seen.get();
    assertThat(context).isNotNull();
    assertThat(context.tenantId()).isEqualTo(1L);
    assertThat(context.organizationId()).isEqualTo(10L);
    assertThat(context.storeId()).isEqualTo(1001L);
    assertThat(context.accountId()).isEqualTo(100L);
    assertThat(context.authorizationVersion()).isEqualTo(7);
    assertThat(context.permissions()).containsExactly("resource.read");
    assertThat(TenantContextHolder.get()).isNull();
  }

  @Test
  void rejectsForgedJsonContext() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, "{\"tenantId\":2,\"accountId\":999,\"authorizationVersion\":0}");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Boolean> invoked = new AtomicReference<>(false);

    filter.doFilter(request, response, (req, res) -> invoked.set(true));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(invoked.get()).isFalse();
  }

  @Test
  void rejectsTokenWithTamperedTenantId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    String token = signedToken(1L, null, null, 100L);
    String[] parts = token.split("\\.");
    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.replace("\"tenantId\":1", "\"tenantId\":2").getBytes(StandardCharsets.UTF_8));
    request.addHeader(TenantContext.HEADER, parts[0] + "." + tampered + "." + parts[2]);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void passesThroughWithoutContext() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    AtomicReference<TenantContext> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(TenantContextHolder.get());

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(seen.get()).isNull();
  }

  @Test
  void resolvesCurrencyClaimAndClearsItAfterRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(1L, null, null, 100L, "CNY"));
    AtomicReference<Currency> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(CurrencyResolver.current());

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(seen.get()).isEqualTo(Currency.CNY);
    assertThat(CurrencyContextHolder.getOrNull()).isNull();
  }

  /**
   * ② 平台作用域上下文：{@code tenantId=0 + scopeType=PLATFORM} 是平台级动作的合法上下文
   * （平台运营未选择租户时，下游仍要能拿到操作人 accountId）。
   */
  @Test
  void acceptsPlatformScopeContextWithoutTenantConstraint() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER,
        signedToken(0L, null, null, 900L, null, TenantContext.SCOPE_PLATFORM));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<TenantContext> seen = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> seen.set(TenantContextHolder.get()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().tenantId()).isZero();
    assertThat(seen.get().accountId()).isEqualTo(900L);
    assertThat(seen.get().platformScope()).isTrue();
  }

  /** 没有 PLATFORM 作用域声明的 token 仍必须 tenantId>0：租户账号不能靠声明 tenantId=0 逃出租户边界。 */
  @Test
  void rejectsZeroTenantIdWithoutPlatformScope() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(0L, null, null, 100L, null, null));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /** 非 PLATFORM 的作用域声明同样不放行 tenantId=0（避免未知 scopeType 被当作平台）。 */
  @Test
  void rejectsZeroTenantIdForNonPlatformScope() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(0L, null, null, 100L, null, "TENANT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /** 老 token（无 currency claim）必须回退 USD，且**不得**被 401 拒绝。 */
  @Test
  void fallsBackToUsdWhenTokenHasNoCurrencyClaim() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(1L, null, null, 100L, null));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<TenantContext> context = new AtomicReference<>();
    AtomicReference<Currency> currency = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> {
      context.set(TenantContextHolder.get());
      currency.set(CurrencyResolver.current());
    });

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(context.get()).isNotNull();
    assertThat(currency.get()).isEqualTo(Currency.USD);
  }

  /** 未知/非法 claim 值同样回退 USD，不得抛给业务。 */
  @Test
  void fallsBackToUsdForUnknownCurrencyClaim() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TenantContext.HEADER, signedToken(1L, null, null, 100L, "RMB"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Currency> currency = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> currency.set(CurrencyResolver.current()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(currency.get()).isEqualTo(Currency.USD);
  }

  private static String signedToken(Long tenantId, Long organizationId, Long storeId, Long accountId) {
    return signedToken(tenantId, organizationId, storeId, accountId, null);
  }

  private static String signedToken(Long tenantId, Long organizationId, Long storeId, Long accountId,
                                    String currency) {
    return signedToken(tenantId, organizationId, storeId, accountId, currency, null);
  }

  private static String signedToken(Long tenantId, Long organizationId, Long storeId, Long accountId,
                                    String currency, String scopeType) {
    return Jwts.builder().subject(accountId.toString()).issuer("gv-saas-identity")
        .audience().add("gv-saas-services").and().claim("tenantId", tenantId)
        .claim("organizationId", organizationId).claim("storeId", storeId)
        .claim("authorizationVersion", 7).claim("permissions", List.of("resource.read"))
        .claim("scopeType", scopeType)
        .claim("currency", currency)
        .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
  }
}
