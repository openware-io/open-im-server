package io.openware.platform.admin.infra;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * IM 用户服务内部端点客户端（RestClient + HMAC）：按用户名解析 IM open_id（"im_" + userId）。
 * 供运营人员开通时把 IM 用户名归一化为 open_id，与 OAuth 绑定的 provider_account_id 对齐。
 */
@Component
public class ImUserResolveClient {

    private static final String OPEN_ID_PREFIX = "im_";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImUserResolveClient(
            @Value("${IM_USER_SERVICE_BASE_URL:http://im-user-service:3100}") String baseUrl,
            InternalServiceAuthenticationInterceptor authenticationInterceptor) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(authenticationInterceptor).requestFactory(requestFactory).build();
    }

    /** 按用户名解析 IM open_id；用户不存在返回 null。 */
    public String resolveOpenId(String username) {
        try {
            String resp = restClient.get()
                    .uri(builder -> builder.path("/internal/admin/users").queryParam("username", username)
                            .queryParam("page", 1).queryParam("pageSize", 1).build())
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            JsonNode items = node == null ? null : node.path("items");
            if (items != null && items.isArray() && items.size() > 0) {
                long userId = items.get(0).path("id").asLong();
                if (userId > 0 && username.equalsIgnoreCase(items.get(0).path("username").asText())) {
                    return OPEN_ID_PREFIX + userId;
                }
            }
            return null;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("调用 IM 用户服务解析 open_id 失败: " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException("调用 IM 用户服务解析 open_id 失败", e);
        }
    }
}
