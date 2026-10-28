package io.openware.platform.order.infra.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 读取当前账号/门店的授权版本，用于旧 token 失效校验。 */
@Component
public class TenantAuthorizationStateClient {

    private static final long CACHE_MILLIS = 5_000L;

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantAuthorizationStateClient(
            @Value("${app.tenant-service.base-url:http://platform-tenant-service:4110}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public int authorizationVersion(Long accountId, Long tenantId, Long organizationId, Long storeId) {
        String key = accountId + ":" + tenantId + ":" + organizationId + ":" + storeId;
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return cached.version;
        }
        Snapshot snapshot = restClient.get()
                .uri(uri -> uri.path("/internal/iam/account/{accountId}/permissions")
                        .queryParam("tenantId", tenantId)
                        .queryParam("organizationId", organizationId == null ? 0L : organizationId)
                        .queryParam("storeId", storeId == null ? 0L : storeId)
                        .queryParam("refresh", true)
                        .build(accountId))
                .retrieve().body(Snapshot.class);
        int version = snapshot == null ? 0 : snapshot.authorizationVersion();
        cache.put(key, new Cached(version, now + CACHE_MILLIS));
        return version;
    }

    public record Snapshot(Long accountId, Long tenantId, Long organizationId, Long storeId,
                           int authorizationVersion) {
    }

    private record Cached(int version, long expiresAt) {
    }
}
