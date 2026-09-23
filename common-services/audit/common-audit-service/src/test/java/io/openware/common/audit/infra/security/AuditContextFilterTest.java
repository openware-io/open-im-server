package io.openware.common.audit.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 运营上下文过滤器回归：租户上下文 / 平台上下文（无租户约束）/ 非法签名 / 缺密钥四种路径，
 * 以及「链内可见、链后清理」的 ThreadLocal 生命周期。
 */
class AuditContextFilterTest {

  private static final String SECRET = "common-audit-jwt-secret-for-tests-32bytes";

  @AfterEach
  void cleanup() {
    TenantContextHolder.clear();
    AuditCallerScopeHolder.clear();
  }

  @Test
  void tenantContextIsAppliedWithPermissionsAndClearedAfterwards() throws Exception {
    AuditContextFilter filter = new AuditContextFilter(SECRET);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    request.addHeader(TenantContext.HEADER, token(77L, 1001L, "TENANT", List.of("audit.view")));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<TenantContext> seenContext = new AtomicReference<>();
    AtomicReference<AuditCallerScope> seenScope = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> {
      seenContext.set(TenantContextHolder.get());
      seenScope.set(AuditCallerScopeHolder.get());
    });

    assertEquals(1001L, seenContext.get().tenantId());
    assertEquals(77L, seenContext.get().accountId());
    assertTrue(seenContext.get().permissions().contains("audit.view"));
    assertFalse(seenScope.get().isPlatform());
    assertNull(TenantContextHolder.get());
    assertNull(AuditCallerScopeHolder.get());
  }

  /** 平台上下文（tenantId=0，无租户约束）+ scopeType=PLATFORM：审计服务据此放行跨租户查询。 */
  @Test
  void platformContextWithoutTenantConstraintCarriesPlatformScope() throws Exception {
    AuditContextFilter filter = new AuditContextFilter(SECRET);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    request.addHeader(TenantContext.HEADER, token(9L, 0L, "PLATFORM", List.of("audit.view")));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<TenantContext> seenContext = new AtomicReference<>();
    AtomicReference<AuditCallerScope> seenScope = new AtomicReference<>();

    filter.doFilter(request, response, (req, res) -> {
      seenContext.set(TenantContextHolder.get());
      seenScope.set(AuditCallerScopeHolder.get());
    });

    assertEquals(0L, seenContext.get().tenantId());
    assertTrue(seenScope.get().isPlatform());
  }

  /** 非法（伪造/过期）上下文必须 401，绝不能按「无上下文」静默放行。 */
  @Test
  void tamperedContextIsRejectedWith401() throws Exception {
    AuditContextFilter filter = new AuditContextFilter(SECRET);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    request.addHeader(TenantContext.HEADER,
        token(77L, 1001L, "TENANT", List.of("audit.view"), "another-secret-32-bytes-long-key-0001"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("INVALID_TENANT_CONTEXT"));
  }

  @Test
  void malformedHeaderIsRejectedWith401WithoutThrowing() throws Exception {
    AuditContextFilter filter = new AuditContextFilter(SECRET);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    request.addHeader(TenantContext.HEADER, "not-a-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(401, response.getStatus());
  }

  @Test
  void missingJwtSecretRefusesToTrustAnyContext() throws Exception {
    AuditContextFilter filter = new AuditContextFilter("");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    request.addHeader(TenantContext.HEADER, token(77L, 1001L, "TENANT", List.of("audit.view")));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("AUDIT_CONTEXT_UNVERIFIABLE"));
  }

  @Test
  void requestWithoutContextHeaderPassesThroughAsAnonymous() throws Exception {
    AuditContextFilter filter = new AuditContextFilter(SECRET);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/audits");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> { });

    assertEquals(200, response.getStatus());
    assertNull(TenantContextHolder.get());
  }

  @Test
  void holderScopeReflectsPlatformClaim() {
    assertTrue(new AuditCallerScope("PLATFORM", 1001L).isPlatform());
    assertFalse(new AuditCallerScope("TENANT", 1001L).isPlatform());
    assertFalse(new AuditCallerScope(null, 1001L).isPlatform());
  }

  private static String token(long accountId, long tenantId, String scopeType, List<String> permissions) {
    return token(accountId, tenantId, scopeType, permissions, SECRET);
  }

  private static String token(long accountId, long tenantId, String scopeType, List<String> permissions,
                              String secret) {
    SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    return Jwts.builder().subject(String.valueOf(accountId)).issuer("open-saas-identity")
        .audience().add("open-saas-services").and()
        .claim("tenantId", tenantId)
        .claim("authorizationVersion", 1)
        .claim("permissions", permissions)
        .claim("scopeType", scopeType)
        .signWith(key).compact();
  }
}
