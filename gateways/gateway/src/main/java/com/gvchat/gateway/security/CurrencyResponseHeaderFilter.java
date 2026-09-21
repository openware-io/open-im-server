package com.gvchat.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关币种兜底（{@code docs/standards/16_CURRENCY_CONVENTIONS.md} §3.5）。
 *
 * <p>在响应上注入 {@code X-Currency}（取自签名上下文，缺省 {@code USD}），并配 CORS
 * {@code exposedHeaders}（见 application.yml），供**漏带 currencyCode 的旧接口**兜底渲染。
 * 兜底不能替代单据快照：记录自身带币种的仍以记录为准。
 *
 * <p>取值来源：{@link SaasSessionAuthenticationFilter} 从服务端会话（Redis）取出并转发到下游的
 * {@code X-Tenant-Context} 短期 JWT。网关自身不持有 JWT 密钥，也不做鉴权结论——该 token 完全由
 * 服务端会话驱动（客户端提交的同名头会被上游过滤器丢弃），因此这里只解析 payload 中的
 * {@code currency} claim；解析失败、缺 claim（老 token）、未知取值一律回退 {@code USD}。
 */
public final class CurrencyResponseHeaderFilter implements GlobalFilter, Ordered {

  /** 与 SDK {@code Currency.DEFAULT} 一致：缺省 USD。 */
  static final String DEFAULT_CURRENCY = "USD";
  static final String HEADER = "X-Currency";
  private static final String TENANT_CONTEXT = "X-Tenant-Context";
  /** 必须晚于会话过滤器（HIGHEST_PRECEDENCE + 5）执行，才能看到它写入的 X-Tenant-Context。 */
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String currency = resolveCurrency(exchange);
    ServerHttpResponse response = exchange.getResponse();
    // 立刻写入，并在提交前再确认一次：下游路由若整体重写响应头也不会把兜底头弄丢。
    response.getHeaders().set(HEADER, currency);
    response.beforeCommit(() -> {
      response.getHeaders().set(HEADER, currency);
      return Mono.empty();
    });
    return chain.filter(exchange);
  }

  /** 从转发的上下文 token 解析币种，缺省 USD；任何异常都不得影响业务请求。 */
  private String resolveCurrency(ServerWebExchange exchange) {
    try {
      String token = exchange.getRequest().getHeaders().getFirst(TENANT_CONTEXT);
      if (token == null || token.isBlank()) {
        return DEFAULT_CURRENCY;
      }
      String[] parts = token.split("\\.");
      if (parts.length < 2) {
        return DEFAULT_CURRENCY;
      }
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      JsonNode node = objectMapper.readTree(payload);
      String currency = node == null ? null : node.path("currency").asText(null);
      return normalize(currency);
    } catch (Exception ex) {
      return DEFAULT_CURRENCY;
    }
  }

  /** 只放行受支持币种代码；未知/空值回退 USD（与 SDK Currency.parse 同口径）。 */
  static String normalize(String currency) {
    if (currency == null) {
      return DEFAULT_CURRENCY;
    }
    String trimmed = currency.trim().toUpperCase(java.util.Locale.ROOT);
    return "CNY".equals(trimmed) || "USD".equals(trimmed) ? trimmed : DEFAULT_CURRENCY;
  }
}
