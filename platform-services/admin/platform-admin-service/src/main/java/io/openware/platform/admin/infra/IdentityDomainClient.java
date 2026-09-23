package io.openware.platform.admin.infra;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;

/**
 * identity-service 内部端点客户端（RestClient）：运营人员开通时按登录标识查/建账号。
 */
@Component
public class IdentityDomainClient {

    private final RestClient identityClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdentityDomainClient(@Value("${IDENTITY_SERVICE_BASE_URL:http://platform-identity-service:4100}") String baseUrl,
                                InternalServiceAuthenticationInterceptor internalAuth) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.identityClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).requestInterceptor(internalAuth).build();
    }

    /**
     * 查某个 IM 登录标识（{@code im_<id>}）在统一账号模型里是否仍有落点（只读，不建号）。
     *
     * <p>走 identity 已有的内部只读端点 {@code GET /internal/accounts/im/{username}}
     * （{@code ImUnifiedAccountApplicationService#findByImUsername}）：返回体里的 {@code found} 为 false
     * 表示该 IM 身份已被清理 —— IM 后台「删除用户」时会把 {@code idt_login_identity(login_type='IM')}
     * 与 {@code idt_oauth_link} 物理删除，员工 / 平台运营账号本体保留。
     * 运营人员**换绑**时用它判断「旧 IM 标识是否仍然存在」。
     *
     * <p>查询失败抛 {@link IllegalStateException}（不吞成 false）：调用方必须 fail-closed
     * —— 宁可拒绝换绑，也不误覆盖一个可能仍然有效的 IM 关联。
     */
    public boolean imIdentityExists(String imLoginIdentifier) {
        try {
            String resp = identityClient.get()
                    .uri("/internal/accounts/im/{username}", imLoginIdentifier)
                    .retrieve()
                    .body(String.class);
            JsonNode node = resp == null ? null : objectMapper.readTree(resp);
            return node != null && node.path("found").asBoolean(false);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("查询 identity 的 IM 账号落点失败: " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException("查询 identity 的 IM 账号落点失败", e);
        }
    }

    /** 按登录标识查/建账号，返回 accountId。 */
    public long ensureAccount(String loginType, String loginIdentifier, String accountType) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "loginType", loginType, "loginIdentifier", loginIdentifier, "accountType", accountType));
            String resp = identityClient.post()
                    .uri("/internal/accounts/ensure")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node.path("id").asLong();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("调用 identity 账号服务失败: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("调用 identity 账号服务失败", e);
        }
    }
}
