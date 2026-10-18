package io.openware.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 网关关联标识：保留客户端值，缺失时生成，并同时写入下游请求与响应。 */
class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void keepsIncomingRequestId() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/ping").header("X-Request-Id", "req-123").build());

    filter.filter(exchange, passThrough()).block();

    assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo("req-123");
  }

  @Test
  void generatesRequestIdWhenMissing() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/ping").build());

    filter.filter(exchange, passThrough()).block();

    assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isNotBlank();
  }

  @Test
  void propagatesRequestIdToDownstreamRequest() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/ping").build());
    final String[] forwarded = new String[1];
    GatewayFilterChain chain = request -> {
      forwarded[0] = request.getRequest().getHeaders().getFirst("X-Request-Id");
      return Mono.empty();
    };

    filter.filter(exchange, chain).block();

    assertThat(forwarded[0]).isNotBlank();
    assertThat(forwarded[0]).isEqualTo(exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
  }

  private static GatewayFilterChain passThrough() {
    return request -> Mono.empty();
  }
}
