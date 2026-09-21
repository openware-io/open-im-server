package com.gvchat.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SaasSessionAuthenticationFilterTest {
  @Test
  void forwardsOnlyServerStoredContextAndStripsClientHeaders() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:session-id")).thenReturn(
        Mono.just("{\"accountId\":100,\"tenantContextToken\":\"signed-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    GatewayFilterChain chain = exchange -> {
      forwarded.set(exchange.getRequest());
      return Mono.empty();
    };
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/me/orders")
        .cookie(new org.springframework.http.HttpCookie("__Host-saas_session", "session-id"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer attacker-token")
        .header("X-Tenant-Context", "forged-context").build());

    filter.filter(exchange, chain).block();

    assertThat(forwarded.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("signed-context");
  }

  /**
   * 后台路由不能被同浏览器的消费端/B 端会话抢走：真机复现——同一浏览器里存在 KTV B 端会话 cookie 时，
   * admin 点「租户后台」→ `/api/v1/admin/tenant/stores` 被消费端会话接管 → 401 → 前端退出登录。
   */
  @Test
  void adminPathUsesAdminSessionEvenWhenConsumerCookiePresent() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session")).thenReturn(
        Mono.just("{\"accountId\":1,\"tenantContextToken\":\"admin-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/admin/tenant/stores")
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "b-end-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("admin-context");
  }

  /** 后台前端显式声明应用族后，租户后台用的 /api/v1/business/** 也必须走后台会话。 */
  @Test
  void adminDeclaredAppUsesAdminSessionForBusinessPaths() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session")).thenReturn(
        Mono.just("{\"accountId\":1,\"tenantContextToken\":\"admin-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/business/orders")
            .header("X-Saas-App", "saas-admin")
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "b-end-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("admin-context");
  }

  /** 移动端 B 端声明 appId 时仍走 B 端会话，不被后台规则影响。 */
  @Test
  void declaredBackendAppStillUsesBackendSessionOnAdminPath() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:b-end-session")).thenReturn(
        Mono.just("{\"accountId\":101,\"tenantContextToken\":\"b-end-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/admin/tenant/stores")
            .header("X-Saas-App", "saas-a380-h5")
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "b-end-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("b-end-context");
  }

  /**
   * 公共媒体对象匿名可读是设计目标：`/api/v1/media-public/**` 不属于任何 SaaS 会话路由前缀，
   * 未带任何 cookie 也必须原样放行（否则 `<img>` 直接 401）。注意 `/api/v1/media/**` 业务接口不受此豁免影响。
   */
  @Test
  void doesNotInterceptAnonymousPublicMediaReads() {
    SaasSessionAuthenticationFilter filter =
        new SaasSessionAuthenticationFilter(mock(ReactiveStringRedisTemplate.class));
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/media-public/gv-media-public/saas/t100/202609/a.png").build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get()).as("匿名公共媒体读取必须放行到路由").isNotNull();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void rejectsRequestWithoutSessionCookie() {
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(mock(ReactiveStringRedisTemplate.class));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/me/orders").build());

    filter.filter(exchange, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void doesNotInterceptImAdminBearerRoutes() {
    SaasSessionAuthenticationFilter filter =
        new SaasSessionAuthenticationFilter(mock(ReactiveStringRedisTemplate.class));
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/users")
        .header(HttpHeaders.AUTHORIZATION, "Bearer im-token").build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer im-token");
  }

  @Test
  void authenticatesWithPlainHttpSessionCookieAlias() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:session-plain")).thenReturn(
        Mono.just("{\"accountId\":157,\"tenantContextToken\":\"signed-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/business/resources")
        .cookie(new org.springframework.http.HttpCookie("saas_c_session", "session-plain")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("signed-context");
  }

  @Test
  void authenticatesPlatformAdminWithoutRequiringTenantContext() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session"))
        .thenReturn(Mono.just("{\"accountId\":1,\"role\":\"PLATFORM\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/platform/tenants")
        .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session"))
        .header("X-Tenant-Context", "forged-context").build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isNull();
  }

  /**
   * ② 回归：平台级路由在「运营未选择租户上下文」时也要带上会话预置的平台作用域 token，
   * 否则下游领域服务的平台级动作（如 tenant.create）落库没有操作人（operator_id 为空）。
   */
  @Test
  void platformRouteForwardsPresetPlatformContextWhenNoTenantContextSelected() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session")).thenReturn(Mono.just(
        "{\"accountId\":1,\"role\":\"PLATFORM_ADMIN\",\"platformContextToken\":\"signed-platform-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/api/v1/admin/platform/tenants")
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session"))
            .header("X-Tenant-Context", "forged-context").build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context"))
        .isEqualTo("signed-platform-context");
  }

  /** 运营已选择租户上下文时租户上下文优先：平台页面的读写仍应受该租户作用域与权限约束。 */
  @Test
  void platformRoutePrefersSelectedTenantContextOverPresetPlatformContext() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session")).thenReturn(Mono.just(
        "{\"accountId\":1,\"tenantContextToken\":\"selected-tenant-context\","
            + "\"platformContextToken\":\"signed-platform-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/admin/platform/tenants")
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context"))
        .isEqualTo("selected-tenant-context");
  }

  /** 平台级路由在两族上下文都没有时仍必须放行（不能从「可用」退化成 401）。 */
  @Test
  void platformRouteWithoutAnyContextStaysAvailable() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas-admin:session:admin-session"))
        .thenReturn(Mono.just("{\"accountId\":1,\"role\":\"PLATFORM_ADMIN\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/admin/platform/tenants")
            .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      // 用 setComplete() 而不是 Mono.empty()：空的 chain 结果会触发外层 switchIfEmpty（401），
      // 那是测试桩的假象，不是被测过滤器在拒绝。
      return requestExchange.getResponse().setComplete();
    }).block();

    assertThat(forwarded.get()).as("平台级路由必须继续放行").isNotNull();
    assertThat(exchange.getResponse().getStatusCode())
        .as("平台级路由不得因为缺少上下文变成 401").isNotEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isNull();
  }

  /**
   * 回归：C 端与 B 端会话 cookie 可以在同一浏览器/WebView 内并存。
   *
   * <p>真机曾出现：消费端会话存在时打开 A380后台，运营请求固定取 C 端会话鉴权，
   * 于是 `POST /business/reservations/{id}/confirm` 返回 403 PERMISSION_DENIED，
   * 页面因缺少错误处理表现为「按钮点不动」。声明应用族后必须取对应族的会话。
   */
  @Test
  void declaredBackendAppUsesBackendSessionWhenBothCookiesPresent() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:consumer-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"consumer-context\"}"));
    when(values.get("saas:user-session:backend-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"operator-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/api/v1/business/reservations/29/confirm")
            .header("X-Saas-App", "saas-a380-h5")
            .cookie(new org.springframework.http.HttpCookie("saas_c_session", "consumer-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "backend-session"))
            .build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("operator-context");
  }

  @Test
  void undeclaredAppKeepsConsumerFirstOrderForOldClients() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:consumer-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"consumer-context\"}"));
    when(values.get("saas:user-session:backend-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"operator-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/business/reservations")
            .cookie(new org.springframework.http.HttpCookie("saas_c_session", "consumer-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "backend-session"))
            .build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("consumer-context");
  }

  @Test
  void declaredConsumerAppUsesConsumerSessionEvenWhenBackendCookieComesFirst() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("saas:user-session:consumer-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"consumer-context\"}"));
    when(values.get("saas:user-session:backend-session")).thenReturn(
        Mono.just("{\"accountId\":133,\"tenantContextToken\":\"operator-context\"}"));
    SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/api/v1/business/reservations")
            .header("X-Saas-App", "saas-a380-c")
            .cookie(new org.springframework.http.HttpCookie("saas_b_session", "backend-session"))
            .cookie(new org.springframework.http.HttpCookie("saas_c_session", "consumer-session"))
            .build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("consumer-context");
  }

  /**
   * 回归：集合端点（无尾部斜杠）必须与其子路径一样被识别为 SaaS 路由。
   *
   * <p>真机曾出现：这些前缀写成带尾部斜杠的形式，`/api/v1/admin/resources`、`/api/v1/admin/daily-closings`、
   * `/api/v1/admin/orders` 等集合端点一个都不匹配，网关按非 SaaS 路由放行且不下发 `X-Tenant-Context`，
   * 下游 MyBatis 租户拦截器抛「租户上下文缺失」，仓库以外的多个一级菜单直接 500。
   */
  @Test
  void forwardsTenantContextForCollectionEndpointsWithoutTrailingSlash() {
    for (String path : new String[] {"/api/v1/admin/resources", "/api/v1/admin/daily-closings", "/api/v1/admin/orders",
        "/api/v1/admin/media/images"}) {
      ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
      @SuppressWarnings("unchecked")
      ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
      when(redis.opsForValue()).thenReturn(values);
      when(values.get("saas-admin:session:admin-session"))
          .thenReturn(Mono.just("{\"accountId\":1,\"tenantContextToken\":\"signed-context\"}"));
      SaasSessionAuthenticationFilter filter = new SaasSessionAuthenticationFilter(redis);
      AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
      MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
          .cookie(new org.springframework.http.HttpCookie("saas_admin_session", "admin-session")).build());

      filter.filter(exchange, requestExchange -> {
        forwarded.set(requestExchange.getRequest());
        return Mono.empty();
      }).block();

      assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).as(path).isEqualTo("signed-context");
    }
  }

  /** 前缀匹配不得放宽成 startsWith：`/api/v1/admin/resourceful` 不是 SaaS 前缀下的路径。 */
  @Test
  void doesNotMatchUnrelatedAdminPathThatMerelySharesAPrefix() {
    SaasSessionAuthenticationFilter filter =
        new SaasSessionAuthenticationFilter(mock(ReactiveStringRedisTemplate.class));
    AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/resourceful")
        .header("X-Tenant-Context", "client-context").build());

    filter.filter(exchange, requestExchange -> {
      forwarded.set(requestExchange.getRequest());
      return Mono.empty();
    }).block();

    // 未命中任何 SaaS 前缀（既有行为，本次不做变更）：不进会话校验，请求头原样透传。
    assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Context")).isEqualTo("client-context");
  }
}
