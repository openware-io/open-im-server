package io.openware.gateway.security;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关关联标识入口（standards/40：HTTP 入口必须生成或传播关联标识）。
 *
 * <p>客户端带来的 {@code X-Request-Id} 原样保留；缺失或空白时生成 UUID。
 * 该标识同时写入转发给下游的请求头与响应头，日志/审计/排障可以按同一个值串联。
 * 本过滤器必须早于认证与会话过滤器，保证后续链路都能看到该头。
 */
public final class RequestIdFilter implements GlobalFilter, Ordered {

  static final String HEADER = "X-Request-Id";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String incoming = request.getHeaders().getFirst(HEADER);
    String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;

    ServerHttpRequest mutated = request.mutate().header(HEADER, requestId).build();
    ServerHttpResponse response = exchange.getResponse();
    response.getHeaders().set(HEADER, requestId);
    response.beforeCommit(() -> {
      response.getHeaders().set(HEADER, requestId);
      return Mono.empty();
    });

    return chain.filter(exchange.mutate().request(mutated).build());
  }
}
