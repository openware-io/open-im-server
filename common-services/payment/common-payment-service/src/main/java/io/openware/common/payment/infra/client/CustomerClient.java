package io.openware.common.payment.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * 组合收款调 platform-customer-service 的内部扣减/归还端点（RestClient）。
 * 租户通过 X-Tenant-Context 头传递（customer 侧 TenantContextFilter 解析后 MyBatis 租户拦截器自动附加 tenant_id）。
 *
 * 内部 HMAC 鉴权占位：每个内部请求附加 X-IM-Service-* 头（serviceName + timestamp + signature），
 * 签名当前为简单比对 sha256(serviceName:timestamp:secret)；真实实现应使用 HMAC 共享密钥（见 SDK
 * InternalServiceAuthentication/InternalServiceAuthenticationInterceptor，可整体替换本占位）。
 */
@Component
public class CustomerClient {
    private static final String SERVICE_NAME = "common-payment-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public CustomerClient(@Value("${app.customer-service.base-url:http://localhost:4160}") String baseUrl,
                          @Value("${app.internal-auth.secret:open-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
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
            long timestamp = System.currentTimeMillis();
            String resp = restClient.post()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson(tenantId, storeId))
                    .header("Idempotency-Key", idempotencyKey)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
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
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson(tenantId, storeId))
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
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

    /** 简单签名占位：sha256(serviceName:timestamp:secret)；真实实现应为 HMAC(共享密钥)。 */
    private String simpleSignature(long timestamp) {
        try {
            String raw = SERVICE_NAME + ":" + timestamp + ":" + internalSecret;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成内部服务签名", e);
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
    public record WalletReleaseRequest(Long customerId, Long amount, String currency, Long orderId,
                                       String idempotencyKey) {}
    public record WalletReleaseResponse(Long walletAccountId, Long customerId, Long availableAmount, Long frozenAmount,
                                        String currencyCode) {}
    public record PointReleaseRequest(Long customerId, Long points, Long orderId, String idempotencyKey) {}
    public record PointReleaseResponse(Long pointAccountId, Long customerId, Long availablePoints, Long frozenPoints) {}
    public record WalletBalanceResponse(Long customerId, Long availableAmount, Long frozenAmount, String currencyCode) {}
    public record PointBalanceResponse(Long customerId, Long availablePoints, Long frozenPoints) {}
}
