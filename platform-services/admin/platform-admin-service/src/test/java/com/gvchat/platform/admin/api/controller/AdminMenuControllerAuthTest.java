package com.gvchat.platform.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.platform.admin.application.AdminMenuApplicationService;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.handler.GlobalExceptionHandler;
import com.gvchat.platform.admin.infra.TenantIamDomainClient;
import com.gvchat.platform.admin.infra.persistence.mapper.TenantPaymentMethodMapper;
import com.gvchat.platform.admin.infra.security.AdminContext;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import com.gvchat.platform.admin.infra.security.AdminTenantContextTokenSigner;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /admin/menus}（网关公开入口 {@code /api/v1/admin/menus}）的鉴权回归。
 *
 * <p>后台会话（Redis，2 小时–30 天）里内嵌的运营上下文 Token 只有 30 分钟有效期，过期是**正常**客户端条件：
 * 必须经统一异常处理器回 401 + {@code {code,message}}，而不是被兜底成 500（曾实测 500 + ExpiredJwtException）。
 */
class AdminMenuControllerAuthTest {
  private static final String SECRET = "saas-admin-platform-context-test-secret-0123456789";

  private final TenantPaymentMethodMapper tenantPaymentMethodMapper = mock(TenantPaymentMethodMapper.class);
  private final TenantIamDomainClient tenantIamClient = mock(TenantIamDomainClient.class);
  private final AdminTenantContextTokenSigner contextTokens =
      new AdminTenantContextTokenSigner(jwtProperties());
  private final MockMvc mvc = MockMvcBuilders
      .standaloneSetup(new AdminMenuController(
          new AdminMenuApplicationService(tenantPaymentMethodMapper, tenantIamClient, contextTokens)))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @AfterEach
  void cleanup() {
    AdminContextHolder.clear();
  }

  /** 回归：会话仍在但上下文 Token 已过期 → 401，不是 500。 */
  @Test
  void expiredSessionContextTokenReturns401InsteadOf500() throws Exception {
    AdminContextHolder.set(platformAdmin(expiredContextToken()));

    mvc.perform(get("/admin/menus"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SAAS_CONTEXT_INVALID"))
        .andExpect(jsonPath("$.message").exists());
  }

  /** 畸形/被篡改的上下文 Token → 401，不是 500。 */
  @Test
  void malformedSessionContextTokenReturns401() throws Exception {
    AdminContextHolder.set(platformAdmin("not-a-jwt"));

    mvc.perform(get("/admin/menus"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SAAS_CONTEXT_INVALID"));
  }

  /**
   * 既有语义不变：会话有效但**未选择**运营上下文时不是 401，只下发平台段菜单
   * （缺失会话本身由 {@code SaaAdminAuthenticationFilter} 回 401 {@code ADMIN_SESSION_MISSING}，
   * 见 {@code SaaAdminAuthenticationFilterTest.missingSessionReturns401}）。
   */
  @Test
  void sessionWithoutSelectedContextStillReturnsPlatformMenus() throws Exception {
    AdminContextHolder.set(platformAdmin(null));

    mvc.perform(get("/admin/menus"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].scope").value("PLATFORM"));
  }

  /** 有效 Token 语义不变：照常返回该上下文的租户段菜单。 */
  @Test
  void validSessionContextTokenStillReturnsTenantMenus() throws Exception {
    when(tenantPaymentMethodMapper.selectCount(any())).thenReturn(0L);
    when(tenantIamClient.permissions(900L, 100L, null, null)).thenReturn(
        new TenantIamDomainClient.PermissionSnapshot(900L, 100L, null, null, 1, List.of()));
    AdminContextHolder.set(platformAdmin(contextTokens.sign(900L, 100L, null, null, 1, List.of())));

    mvc.perform(get("/admin/menus").param("scope", "TENANT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].scope").value("TENANT"));
  }

  private static AdminContext platformAdmin(String tenantContextToken) {
    return new AdminContext(1L, "admin", "运营管理员", AdminRole.PLATFORM_ADMIN, 900L, tenantContextToken);
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
