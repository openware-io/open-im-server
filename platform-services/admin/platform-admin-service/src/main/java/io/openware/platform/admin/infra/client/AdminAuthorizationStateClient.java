package io.openware.platform.admin.infra.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 读取当前租户上下文快照版本，用于 admin 旧 token 失效。 */
@Component
public class AdminAuthorizationStateClient {

    private static final long CACHE_MILLIS = 5_000L;

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AdminAuthorizationStateClient(
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
