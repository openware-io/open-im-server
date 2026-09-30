package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Payment 域总部汇总客户端；只通过内部 v2 签名接口读取 Payment 自有事实。 */
@Component
public class PaymentOverviewDomainClient {
  private final RestClient client;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PaymentOverviewDomainClient(
      @Value("${PAYMENT_SERVICE_BASE_URL:http://common-payment-service:4140}") String baseUrl,
      InternalServiceAuthenticationInterceptor interceptor) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
        .requestInterceptor(interceptor).build();
  }

  public Map<String, Object> overview(String from, String to, String storeIds) {
    String token = AdminContextHolder.get() == null ? null : AdminContextHolder.get().tenantContextToken();
    if (token == null || token.isBlank()) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择经营上下文");
    }
    StringBuilder uri = new StringBuilder("/internal/payment/overview");
    char separator = '?';
    for (String[] pair : new String[][]{{"from", from}, {"to", to}, {"storeIds", storeIds}}) {
      if (pair[1] != null && !pair[1].isBlank()) {
        uri.append(separator).append(pair[0]).append('=').append(URLEncoder.encode(
            pair[1], StandardCharsets.UTF_8));
        separator = '&';
      }
    }
    try {
      String response = client.get().uri(uri.toString()).header("X-Tenant-Context", token)
          .retrieve().body(String.class);
      return objectMapper.convertValue(objectMapper.readTree(response), Map.class);
    } catch (RestClientResponseException e) {
      throw new ApiException(e.getStatusCode().value(), "PAYMENT_OVERVIEW_FAILED", "支付汇总查询失败");
    } catch (Exception e) {
      throw new ApiException(504, "PAYMENT_OVERVIEW_TIMEOUT", "支付汇总查询超时");
    }
  }
}
