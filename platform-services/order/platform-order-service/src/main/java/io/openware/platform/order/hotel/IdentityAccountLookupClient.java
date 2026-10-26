package io.openware.platform.order.hotel;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 后台代客下单：按住客手机号解析 SaaS 账号，用于 C 端“我的预约”归属。 */
@Component
@Slf4j
public class IdentityAccountLookupClient {
    private final RestClient restClient;

    public IdentityAccountLookupClient(
            @Value("${IDENTITY_SERVICE_BASE_URL:http://platform-identity-service:4100}") String baseUrl,
            InternalServiceAuthenticationInterceptor internalAuth) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestInterceptor(internalAuth).build();
    }

    public Optional<Long> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        try {
            Long accountId = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/identity/accounts/lookup")
                            .queryParam("loginType", "PHONE")
                            .queryParam("loginIdentifier", phone.trim()).build())
                    .retrieve().body(Long.class);
            return accountId == null ? Optional.empty() : Optional.of(accountId);
        } catch (RestClientResponseException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("按手机号解析 SaaS 账号失败，按未关联处理: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
