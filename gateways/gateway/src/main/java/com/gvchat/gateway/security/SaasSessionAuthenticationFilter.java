package com.gvchat.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves the HttpOnly SaaS user session and forwards only its signed, server-held tenant context.
 * Client supplied Authorization and X-Tenant-Context headers are deliberately discarded.
 */
@Slf4j
public final class SaasSessionAuthenticationFilter implements GlobalFilter, Ordered {
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;
  private static final String C_COOKIE = "__Host-saas_c_session";
  private static final String B_COOKIE = "__Host-saas_b_session";
  private static final String LEGACY_COOKIE = "__Host-saas_session";
  /** Plain-http aliases written when saas.cookie.plain=true (local Kind over plain http). */
  private static final String C_COOKIE_PLAIN = "saas_c_session";
  private static final String B_COOKIE_PLAIN = "saas_b_session";
  private static final String ADMIN_COOKIE = "saas_admin_session";
  /** 客户端声明自身应用族的请求头（C 端/B 端各自填写自己的 appId）。 */
  private static final String SAAS_APP_HEADER = "X-Saas-App";
  /** SaaS 后台前端声明的应用族（X-Saas-App: saas-admin）。 */
  private static final String ADMIN_APP = "saas-admin";
  /** SaaS 后台独占的受保护路由前缀（未声明应用族时据此判定为后台请求）。 */
  private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";
  private static final String SESSION_PREFIX = "saas:user-session:";
  private static final String TENANT_CONTEXT = "X-Tenant-Context";
  /** SaaS 后台（平台运营后台 / 租户后台）受会话与运营上下文保护的路由前缀；一律不带尾部斜杠。 */
  private static final String[] SAAS_ADMIN_PREFIXES = {
      "/api/v1/admin/platform", "/api/v1/admin/tenant", "/api/v1/admin/iam",
      "/api/v1/admin/pricing-plans", "/api/v1/admin/resources", "/api/v1/admin/refund-requests",
      "/api/v1/admin/daily-closings", "/api/v1/admin/reconciliations", "/api/v1/admin/payment-channels",
      "/api/v1/admin/payment-methods", "/api/v1/admin/ktv", "/api/v1/admin/reservations",
      "/api/v1/admin/staff", "/api/v1/admin/reports", "/api/v1/admin/wallets",
      "/api/v1/admin/audits", "/api/v1/admin/channels", "/api/v1/admin/inventory",
      "/api/v1/admin/products", "/api/v1/admin/orders", "/api/v1/admin/media"
  };
  private final ReactiveStringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SaasSessionAuthenticationFilter(ReactiveStringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    Route route = routeFor(path);
    if (route == null) {
      return chain.filter(exchange);
    }
    // 会话族选择。
    //
    // 背景（2026-09-16 真机复现）：同一浏览器里既有 KTV 消费端/B 端会话、又有 SaaS 后台会话时，
    // 旧逻辑永远先取 C/B cookie，后台请求（/api/v1/admin/**，以及后台租户页用的 /api/v1/business/**）
    // 会被消费端会话接管：消费端会话没有运营上下文 → 网关 401 →
    // 后台前端按 401 退出登录（现象：admin 点「租户后台」闪退回登录页 + 「加载门店失败」）。
    // 现在：声明了 saas-admin、或未声明应用族而路径是 /api/v1/admin/**（该前缀只可能来自后台前端）
    // 时，一律用后台会话 cookie；C 端/B 端按原有声明规则解析，行为不变。
    SessionRef session = resolveSession(exchange, path);
    if (session == null) {
      return reject(exchange, "SAAS_SESSION_REQUIRED");
    }
    String prefix = session.prefix();
    String rawSessionId = session.id();
    return redisTemplate.opsForValue().get(prefix + rawSessionId)
        // 这里必须用 defaultIfEmpty 占位，而不是在 flatMap 之后接 switchIfEmpty：
        // 下面每个分支都返回 Mono<Void>（转发/拒绝都不会 emit 元素），而 switchIfEmpty 只看
        // 「完成时有没有元素」——于是**成功转发之后**也会命中「缺少会话」分支，把已经代理成功的
        // 响应再改写成 401（历史上平台级路由看似 401 却又能拿到数据的根因）。
        .defaultIfEmpty("")
        .flatMap(raw -> {
          if (raw.isEmpty()) {
            return reject(exchange, "SAAS_SESSION_REQUIRED");
          }
          if (route == Route.SESSION_ONLY) {
            return forward(exchange, chain, null);
          }
          if (route == Route.PLATFORM_CONTEXT) {
            // 平台级路由：优先下发运营选择的租户上下文；未选择上下文时下发会话预置的平台作用域
            // token（tenantId=0 + scopeType=PLATFORM），保证平台级动作（如创建租户）在下游能拿到
            // 操作人。两者都没有时按 SESSION_ONLY 放行（**不新增 401**，保持既有可用性）。
            String token = contextToken(raw).orElseGet(() -> platformContextToken(raw).orElse(null));
            return forward(exchange, chain, token);
          }
          return contextToken(raw).map(token -> forward(exchange, chain, token))
              .orElseGet(() -> reject(exchange, "SAAS_CONTEXT_REQUIRED"));
        })
        .onErrorResume(error -> reject(exchange, "SAAS_SESSION_UNAVAILABLE"));
  }

  /** B 端应用族判定（与 identity 服务同一规则）：`*-h5` 或含 `-b`。 */
  private static boolean isBackendApp(String appId) {
    return appId != null && (appId.endsWith("-h5") || appId.contains("-b"));
  }

  /** SaaS 后台前端声明的应用族。 */
  private static boolean isAdminApp(String appId) {
    return appId != null && ADMIN_APP.equalsIgnoreCase(appId.trim());
  }

  /**
   * 解析本次请求该用哪一族会话 cookie。
   *
   * <p>返回 {@code null} 表示三族 cookie 都没有，按 401 处理。</p>
   */
  private static SessionRef resolveSession(ServerWebExchange exchange, String path) {
    String declaredApp = exchange.getRequest().getHeaders().getFirst(SAAS_APP_HEADER);
    boolean adminRequest = isAdminApp(declaredApp)
        || (declaredApp == null && matchesPath(path, ADMIN_PATH_PREFIX));
    if (adminRequest) {
      String adminSession = firstCookieValue(exchange, ADMIN_COOKIE);
      return adminSession == null ? null : new SessionRef(adminSession, "saas-admin:session:");
    }
    if (isBackendApp(declaredApp)) {
      String raw = firstCookieValue(exchange, B_COOKIE, B_COOKIE_PLAIN, LEGACY_COOKIE, C_COOKIE, C_COOKIE_PLAIN);
      return raw == null ? null : new SessionRef(raw, SESSION_PREFIX);
    }
    // 未声明应用族（老客户端）：保持既有顺序 C -> B -> 旧名 -> plain，行为不变。
    String raw = firstCookieValue(exchange, C_COOKIE, B_COOKIE, LEGACY_COOKIE, C_COOKIE_PLAIN, B_COOKIE_PLAIN);
    if (raw != null) return new SessionRef(raw, SESSION_PREFIX);
    String adminSession = firstCookieValue(exchange, ADMIN_COOKIE);
    return adminSession == null ? null : new SessionRef(adminSession, "saas-admin:session:");
  }

  private record SessionRef(String id, String prefix) { }

  /** 按给定顺序返回第一个存在且非空的 cookie 值。 */
  private static String firstCookieValue(ServerWebExchange exchange, String... names) {
    var cookies = exchange.getRequest().getCookies();
    for (String name : names) {
      var cookie = cookies.getFirst(name);
      if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private static Route routeFor(String path) {
    if (path == null) return null;
    if (matchesPath(path, "/api/v1/business") || matchesPath(path, "/api/v1/me")) {
      return Route.CONTEXT_BOUND;
    }
    if (!matchesPath(path, "/api/v1/admin")) return null;
    for (String prefix : SAAS_ADMIN_PREFIXES) {
      if (matchesPath(path, prefix)) {
        // 平台级路由（/admin/platform/**、/admin/pricing-plans/**）不要求租户上下文：
        // 它们服务的是平台运营本身；但仍尽量下发可用的签名上下文（见 PLATFORM_CONTEXT）。
        if (matchesPath(path, "/api/v1/admin/platform") || matchesPath(path, "/api/v1/admin/pricing-plans")) {
          return Route.PLATFORM_CONTEXT;
        }
        return Route.CONTEXT_BOUND;
      }
    }
    return null;
  }

  /**
   * 前缀匹配必须同时命中「集合端点」与「集合端点下的子路径」。
   *
   * <p>历史实现把这些前缀写成带尾部斜杠的形式（如 `/api/v1/admin/resources/`），于是集合端点
   * `/api/v1/admin/resources`、`/api/v1/admin/daily-closings`、`/api/v1/admin/orders` 一个都不匹配：
   * 网关按「非 SaaS 路由」放行且不下发 `X-Tenant-Context`，下游 MyBatis 租户拦截器随即抛
   * 「租户上下文缺失」，页面拿到未处理的 500。`/api/v1/admin/products` 曾单独打过补丁，这里统一收口。
   */
  private static boolean matchesPath(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  private enum Route {
    /** 只校验会话，不下发上下文（如平台级路由在无可用上下文时的退化形态）。 */
    SESSION_ONLY,
    /** 必须下发签名租户上下文，缺失即 401。 */
    CONTEXT_BOUND,
    /**
     * 平台级路由：可选下发签名上下文——优先运营选择的租户上下文，其次会话预置的平台作用域 token。
     *
     * <p>优先级不能颠倒：运营选择租户上下文后，平台页面的读写都应带上该租户的作用域与权限；
     * 只有「未选择上下文」的纯平台动作（如创建租户）才需要 tenantId=0 的平台 token 来补操作人。
     */
    PLATFORM_CONTEXT;
  }

  private java.util.Optional<String> contextToken(String raw) {
    return tokenClaim(raw, "tenantContextToken");
  }

  /** 后台会话预置的平台作用域 token（由平台后台服务用同一密钥签发，下游仍会验签）。 */
  private java.util.Optional<String> platformContextToken(String raw) {
    return tokenClaim(raw, "platformContextToken");
  }

  private java.util.Optional<String> tokenClaim(String raw, String field) {
    try {
      JsonNode session = objectMapper.readTree(raw);
      String token = session.path(field).asText("");
      return token.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(token);
    } catch (Exception ex) {
      // 会话数据损坏与「未选定上下文」都会走到 SAAS_CONTEXT_REQUIRED，必须留下可定位的 WARN。
      log.warn("SaaS session payload is not readable, treated as missing tenant context", ex);
      return java.util.Optional.empty();
    }
  }

  private static Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String contextToken) {
    var request = exchange.getRequest().mutate().headers(headers -> {
      headers.remove(HttpHeaders.AUTHORIZATION);
      headers.remove(TENANT_CONTEXT);
      if (contextToken != null) {
        headers.set(TENANT_CONTEXT, contextToken);
      }
    }).build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  private static Mono<Void> reject(ServerWebExchange exchange, String code) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.UNAUTHORIZED);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    // 对外 message 一律简体中文（此前所有码都复用英文 "SaaS session is required"）。
    String message = switch (code) {
      case "SAAS_CONTEXT_REQUIRED" -> "请先选择运营上下文";
      case "SAAS_SESSION_UNAVAILABLE" -> "登录状态暂时不可用，请稍后重试";
      default -> "登录状态已失效，请重新登录";
    };
    byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
