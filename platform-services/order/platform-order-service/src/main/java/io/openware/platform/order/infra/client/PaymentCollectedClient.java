package io.openware.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 读 payment 域的「订单已收分项」（内部只读 HTTP）：账单需要按 现金/A380币/积分 分腿展示，
 * 而组合收款的分腿数据在 pay_collect，属于 payment 域，order 域不直接读它的表。
 *
 * <p>降级：payment 不可达 / 上下文缺失 / 返回异常时返回 empty，账单退回「已收合计记现金」的兜底口径，
 * 绝不让账单读取失败影响主流程。
 */
@Component
public class PaymentCollectedClient {
    private static final String SERVICE_NAME = "platform-order-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public PaymentCollectedClient(@Value("${app.payment-service.base-url:http://common-payment-service:4140}") String baseUrl,
                                  @Value("${app.internal-auth.secret:open-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
    }

    /** 返回订单已收分项；不可用时 empty。 */
    public Optional<CollectedBreakdown> collected(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        String token = TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri("/internal/payment/orders/{orderId}/collected", orderId)
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return Optional.of(new CollectedBreakdown(
                    node.path("cash").asLong(), node.path("wallet").asLong(), node.path("points").asLong()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 简单签名占位：sha256(serviceName:timestamp:secret)，与各服务内部鉴权同口径。 */
    private String simpleSignature(long timestamp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SERVICE_NAME + ":" + timestamp + ":" + internalSecret).getBytes(StandardCharsets.UTF_8));
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

    public record CollectedBreakdown(long cash, long wallet, long points) {}
}
