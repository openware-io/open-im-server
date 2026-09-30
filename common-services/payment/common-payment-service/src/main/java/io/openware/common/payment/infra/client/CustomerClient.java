package io.openware.common.payment.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;


/**
 * 组合收款调 platform-customer-service 的内部扣减/归还端点（RestClient）。
 * 租户通过 X-Tenant-Context 头传递（customer 侧 TenantContextFilter 解析后 MyBatis 租户拦截器自动附加 tenant_id）。
 *
 * 内部请求由 SDK 的鉴权版本 2 拦截器签名；服务端会校验 HMAC、时间窗和写请求防重放。
 */
@Component
public class CustomerClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public CustomerClient(@Value("${app.customer-service.base-url:http://localhost:4160}") String baseUrl,
                          InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    public WalletDeductResponse deductWallet(Long tenantId, Long storeId, Long customerId, Long amount, String currency,
                                             Long orderId, String idempotencyKey) {
        JsonNode node = post("/internal/customer/wallets/deduct", tenantId, storeId,
                new WalletDeductRequest(customerId, amount, currency, orderId, idempotencyKey), idempotencyKey);
        return new WalletDeductResponse(node.path("walletAccountId").asLong(), node.path("customerId").asLong(),
                node.path("availableAmount").asLong(), node.path("frozenAmount").asLong(),
                node.path("currencyCode").asText());
    }

    public PointRedeemResponse redeemPoints(Long tenantId, Long storeId, Long customerId, Long points, Long orderId,
                                            String idempotencyKey) {
        JsonNode node = post("/internal/customer/points/redeem", tenantId, storeId,
                new PointRedeemRequest(customerId, points, orderId, idempotencyKey), idempotencyKey);
        return new PointRedeemResponse(node.path("pointAccountId").asLong(), node.path("customerId").asLong(),
                node.path("availablePoints").asLong(), node.path("frozenPoints").asLong());
    }

    /** 按 Customer 域当前门店/业态规则，将抵扣金额（最小货币单位）换算为应扣积分。 */
    public long pointsForAmount(Long tenantId, Long storeId, long amountMinor) {
        try {
            JsonNode node = restClient.get().uri(uriBuilder -> uriBuilder
                            .path("/internal/customer/points/amount-to-points")
                            .queryParam("amountMinor", amountMinor).build())
                    .header("X-Tenant-Context", tenantContextJson(tenantId, storeId))
                    .retrieve().body(JsonNode.class);
            return node.path("points").asLong();
        } catch (RestClientResponseException e) {
            throw toApiException(e);
        } catch (Exception e) {
            throw new IllegalStateException("调用 customer 服务积分金额换算失败", e);
        }
    }

    public PointEarnResponse earnPoints(Long tenantId, Long storeId, Long customerId, long eligibleAmountMinor,
                                        Long orderId, String idempotencyKey) {
        JsonNode node = post("/internal/customer/points/earn", tenantId, storeId,
                new PointEarnRequest(customerId, eligibleAmountMinor, orderId, idempotencyKey), idempotencyKey);
        return new PointEarnResponse(node.path("pointAccountId").asLong(), node.path("customerId").asLong(),
                node.path("availablePoints").asLong(), node.path("frozenPoints").asLong());
    }

    /** 储值归还（组合收款失败补偿，RELEASE，幂等）。 */
    public WalletReleaseResponse releaseWallet(Long tenantId, Long storeId, Long customerId, Long amount, String currency,
                                               Long orderId, String idempotencyKey) {
        JsonNode node = post("/internal/customer/wallets/release", tenantId, storeId,
                new WalletReleaseRequest(customerId, amount, currency, orderId, idempotencyKey), idempotencyKey);
        return new WalletReleaseResponse(node.path("walletAccountId").asLong(), node.path("customerId").asLong(),
                node.path("availableAmount").asLong(), node.path("frozenAmount").asLong(),
                node.path("currencyCode").asText());
    }

    /** 积分归还（组合收款失败补偿，REVERSE，幂等）。 */
    public PointReleaseResponse releasePoints(Long tenantId, Long storeId, Long customerId, Long points, Long orderId,
                                              String idempotencyKey) {
        JsonNode node = post("/internal/customer/points/release", tenantId, storeId,
                new PointReleaseRequest(customerId, points, orderId, idempotencyKey), idempotencyKey);
        return new PointReleaseResponse(node.path("pointAccountId").asLong(), node.path("customerId").asLong(),
                node.path("availablePoints").asLong(), node.path("frozenPoints").asLong());
    }

    public WalletBalanceResponse walletBalance(Long tenantId, Long storeId, Long customerId) {
        JsonNode node = get("/internal/customer/wallets/" + customerId, tenantId, storeId);
        return new WalletBalanceResponse(node.path("customerId").asLong(), node.path("availableAmount").asLong(),
                node.path("frozenAmount").asLong(), node.path("currencyCode").asText());
    }

    public PointBalanceResponse pointsBalance(Long tenantId, Long storeId, Long customerId) {
        JsonNode node = get("/internal/customer/points/" + customerId, tenantId, storeId);
        return new PointBalanceResponse(node.path("customerId").asLong(), node.path("availablePoints").asLong(),
                node.path("frozenPoints").asLong());
    }

    private JsonNode post(String uri, Long tenantId, Long storeId, Object body, String idempotencyKey) {
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            String resp = restClient.post()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson(tenantId, storeId))
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyJson)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(resp);
        } catch (RestClientResponseException e) {
            throw toApiException(e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用 customer 服务失败: " + uri, e);
        }
    }

    private JsonNode get(String uri, Long tenantId, Long storeId) {
        try {
            String resp = restClient.get()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson(tenantId, storeId))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(resp);
        } catch (RestClientResponseException e) {
            throw toApiException(e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用 customer 服务失败: " + uri, e);
        }
    }

    private String tenantContextJson(Long tenantId, Long storeId) {
        String token = io.openware.infrastructure.tenant.TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank()) throw new IllegalStateException("缺少已验证的租户上下文");
        return token;
    }

    private ApiException toApiException(RestClientResponseException e) {
        String code = null;
        String message = e.getStatusText();
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("code")) code = node.get("code").asText();
            if (node.hasNonNull("message")) message = node.get("message").asText();
        } catch (Exception ignored) {
            // 响应体非 JSON 时退回默认信息
        }
        return new ApiException(e.getStatusCode().value(), code, message);
    }

    public record WalletDeductRequest(Long customerId, Long amount, String currency, Long orderId,
                                      String idempotencyKey) {}
    public record WalletDeductResponse(Long walletAccountId, Long customerId, Long availableAmount, Long frozenAmount,
                                       String currencyCode) {}
    public record PointRedeemRequest(Long customerId, Long points, Long orderId, String idempotencyKey) {}
    public record PointRedeemResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record PointEarnRequest(Long customerId, long eligibleAmountMinor, Long orderId, String idempotencyKey) {}
    public record PointEarnResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record WalletReleaseRequest(Long customerId, Long amount, String currency, Long orderId,
                                       String idempotencyKey) {}
    public record WalletReleaseResponse(Long walletAccountId, Long customerId, Long availableAmount, Long frozenAmount,
                                        String currencyCode) {}
    public record PointReleaseRequest(Long customerId, Long points, Long orderId, String idempotencyKey) {}
    public record PointReleaseResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record WalletBalanceResponse(Long customerId, Long availableAmount, Long frozenAmount, String currencyCode) {}
    public record PointBalanceResponse(Long customerId, Long availablePoints, Long frozenPoints) {}
}
