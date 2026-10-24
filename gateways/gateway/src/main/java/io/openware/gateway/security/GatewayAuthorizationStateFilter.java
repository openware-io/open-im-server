package io.openware.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 集中校验 B/管理面 tenantContextToken 的授权版本，避免只依赖下游服务。 */
@Component
public class GatewayAuthorizationStateFilter implements GlobalFilter, Ordered {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient tenantClient;

    public GatewayAuthorizationStateFilter(
            WebClient.Builder builder,
            @Value("${app.tenant-service.base-url:http://platform-tenant-service:4110}") String baseUrl) {
        this.tenantClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getAttribute("tenantContextToken");
        if (token == null || token.isBlank()) {
            return chain.filter(exchange);
        }
        JsonNode claims;
        try {
            claims = MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        } catch (Exception invalid) {
            return unauthorized(exchange);
        }
        String clientApp = claims.path("clientApp").asText("");
        if (!isBackoffice(clientApp) || "PLATFORM".equals(claims.path("scopeType").asText())) {
            return chain.filter(exchange);
        }
        long accountId = Long.parseLong(claims.path("sub").asText());
        long tenantId = claims.path("tenantId").asLong();
        long organizationId = claims.path("organizationId").asLong();
        long storeId = claims.path("storeId").asLong();
        int tokenVersion = claims.path("authorizationVersion").asInt();
        return tenantClient.get()
                .uri(uri -> uri.path("/internal/iam/account/{accountId}/permissions")
                        .queryParam("tenantId", tenantId)
                        .queryParam("organizationId", organizationId)
                        .queryParam("storeId", storeId)
                        .queryParam("refresh", true)
                        .build(accountId))
                .retrieve()
                .bodyToMono(Snapshot.class)
                .flatMap(snapshot -> {
                    if (snapshot == null || snapshot.authorizationVersion() <= 0
                            || tokenVersion < snapshot.authorizationVersion()) {
                        return unauthorized(exchange);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> unavailable(exchange));
    }

    private static boolean isBackoffice(String appId) {
        return appId != null && ("saas-b".equals(appId) || "saas-admin".equals(appId)
                || appId.endsWith("-h5"));
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        return write(exchange, HttpStatus.UNAUTHORIZED, "AUTHORIZATION_STALE");
    }

    private static Mono<Void> unavailable(ServerWebExchange exchange) {
        return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_STATE_UNAVAILABLE");
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    public record Snapshot(Long accountId, Long tenantId, Long organizationId, Long storeId,
                           int authorizationVersion) {
    }
}
