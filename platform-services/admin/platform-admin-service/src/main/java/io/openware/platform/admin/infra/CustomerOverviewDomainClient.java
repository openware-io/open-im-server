package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import java.time.Duration;
import java.util.Map;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;
/** Customer 总部汇总内部客户端；只传递已签名 TenantContext，不携带可伪造租户字段。 */
@Component
public class CustomerOverviewDomainClient {

    /**
     * 用 TypeReference 而不是 {@code convertValue(..., Map.class)}：后者传裸 {@code Class}，
     * 赋给 {@code Map<String, Object>} 会产生「未经检查的转换」告警，且要先建树再转换。
     * TypeReference 保留了泛型信息，直接把响应体反序列化成目标类型。
     */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public CustomerOverviewDomainClient(
            @Value("${CUSTOMER_SERVICE_BASE_URL:http://platform-customer-service:4160}") String baseUrl,
            InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    public Map<String, Object> overview(String from, String to, String storeIds) {
        String token = AdminContextHolder.get() == null ? null : AdminContextHolder.get().tenantContextToken();
        if (token == null || token.isBlank()) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择经营上下文");
        }
        StringBuilder uri = new StringBuilder("/internal/customer/overview");
        char separator = '?';
        for (String[] pair : new String[][]{{"from", from}, {"to", to}, {"storeIds", storeIds}}) {
            if (pair[1] != null && !pair[1].isBlank()) {
                uri.append(separator).append(pair[0]).append('=').append(java.net.URLEncoder.encode(
                        pair[1], StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        try {
            String response = client.get().uri(uri.toString()).header("X-Tenant-Context", token)
                    .retrieve().body(String.class);
            return objectMapper.readValue(response, MAP_TYPE);
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value(), "CUSTOMER_OVERVIEW_FAILED", "客户汇总查询失败");
        } catch (Exception e) {
            throw new ApiException(504, "CUSTOMER_OVERVIEW_TIMEOUT", "客户汇总查询超时");
        }
    }

}
