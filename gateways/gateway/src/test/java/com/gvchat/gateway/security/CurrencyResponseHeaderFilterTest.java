package com.gvchat.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关币种兜底（规范 §3.5）：响应注入 {@code X-Currency}，取自上下文 token 的 currency claim，
 * 缺省 USD；老 token（无 claim）与非法值一律 USD，且不影响请求放行。
 */
class CurrencyResponseHeaderFilterTest {

  private final CurrencyResponseHeaderFilter filter = new CurrencyResponseHeaderFilter();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void defaultsToUsdWhenNoTenantContext() {
    MockServerWebExchange exchange = exchange(null);

    filter.filter(exchange, passThrough()).block();

    assertThat(exchange.getResponse().getHeaders().getFirst("X-Currency")).isEqualTo("USD");
  }

  @Test
  void injectsCnyFromContextTokenClaim() {
    MockServerWebExchange exchange = exchange(jwt(Map.of("currency", "CNY")));

    filter.filter(exchange, passThrough()).block();

    assertThat(exchange.getResponse().getHeaders().getFirst("X-Currency")).isEqualTo("CNY");
  }

  /** 老 token：payload 里没有 currency claim → 兜底 USD，不得 500/401。 */
  @Test
  void fallsBackToUsdForLegacyTokenWithoutCurrencyClaim() {
    MockServerWebExchange exchange = exchange(jwt(Map.of("tenantId", 100)));

    filter.filter(exchange, passThrough()).block();

    assertThat(exchange.getResponse().getHeaders().getFirst("X-Currency")).isEqualTo("USD");
  }

  @Test
  void fallsBackToUsdForUnknownOrMalformedValues() {
    assertThat(CurrencyResponseHeaderFilter.normalize("RMB")).isEqualTo("USD");
    assertThat(CurrencyResponseHeaderFilter.normalize("")).isEqualTo("USD");
    assertThat(CurrencyResponseHeaderFilter.normalize(null)).isEqualTo("USD");
    assertThat(CurrencyResponseHeaderFilter.normalize("cny")).isEqualTo("CNY");

    MockServerWebExchange exchange = exchange("not-a-jwt");
    filter.filter(exchange, passThrough()).block();
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Currency")).isEqualTo("USD");
  }

  @Test
  void filterRunsAfterSaasSessionAuthenticationFilter() {
    assertThat(filter.getOrder())
        .isGreaterThan(new SaasSessionAuthenticationFilter(null).getOrder());
  }

  private static MockServerWebExchange exchange(String tenantContextToken) {
    MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/v1/business/orders");
    if (tenantContextToken != null) {
      builder = builder.header("X-Tenant-Context", tenantContextToken);
    }
    return MockServerWebExchange.from(builder.build());
  }

  private static GatewayFilterChain passThrough() {
    return exchange -> Mono.empty();
  }

  /** 只需 payload 可解析：网关不持有 JWT 密钥，也不在本过滤器里做鉴权结论。 */
  private String jwt(Map<String, Object> claims) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>(claims);
      String encoded = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(objectMapper.writeValueAsBytes(payload));
      return "header." + encoded + ".signature";
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void payloadDecodingUsesBase64UrlWithoutPadding() {
    String token = jwt(Map.of("currency", "CNY"));
    String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"currency\":\"CNY\"");
  }
}
