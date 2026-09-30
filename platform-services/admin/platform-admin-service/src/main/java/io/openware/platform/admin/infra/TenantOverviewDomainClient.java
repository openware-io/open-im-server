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

/** Tenant 域总部门店/业态汇总客户端；Admin 不直连 Tenant 数据库。 */
@Component
public class TenantOverviewDomainClient {
    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TenantOverviewDomainClient(
            @Value("${TENANT_SERVICE_BASE_URL:http://platform-tenant-service:4110}") String baseUrl,
            InternalServiceAuthenticationInterceptor interceptor) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(interceptor).build();
    }

    public Map<String, Object> overview(String storeIds, String businessType) {
        return get("/internal/tenant/overview", new String[][]{{"storeIds", storeIds}, {"businessType", businessType}},
                "TENANT_OVERVIEW_FAILED", "租户门店汇总查询失败", "TENANT_OVERVIEW_TIMEOUT", "租户门店汇总查询超时");
    }

    private Map<String, Object> get(String path, String[][] params, String failureCode, String failureMessage,
                                    String timeoutCode, String timeoutMessage) {
        String token = AdminContextHolder.get() == null ? null : AdminContextHolder.get().tenantContextToken();
        if (token == null || token.isBlank()) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择经营上下文");
        }
        StringBuilder uri = new StringBuilder(path);
        char separator = '?';
        for (String[] pair : params) {
            if (pair[1] != null && !pair[1].isBlank()) {
                uri.append(separator).append(pair[0]).append('=').append(URLEncoder.encode(pair[1], StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        try {
            String response = client.get().uri(uri.toString()).header("X-Tenant-Context", token)
                    .retrieve().body(String.class);
            return objectMapper.convertValue(objectMapper.readTree(response), Map.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value(), failureCode, failureMessage);
        } catch (Exception e) {
            throw new ApiException(504, timeoutCode, timeoutMessage);
        }
    }
}
