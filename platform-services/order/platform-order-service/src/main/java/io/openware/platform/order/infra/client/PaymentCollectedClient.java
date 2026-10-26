package io.openware.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * 读 payment 域的「订单已收分项」（内部只读 HTTP）：账单需要按 现金/OpenChat币/积分 分腿展示，
 * 而组合收款的分腿数据在 pay_collect，属于 payment 域，order 域不直接读它的表。
 *
 * <p>降级：payment 不可达 / 上下文缺失 / 返回异常时返回 empty，账单退回「已收合计记现金」的兜底口径，
 * 绝不让账单读取失败影响主流程。
 */
@Component
@Slf4j
public class PaymentCollectedClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentCollectedClient(@Value("${app.payment-service.base-url:http://common-payment-service:4140}") String baseUrl,
                                  InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
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
            String resp = restClient.get()
                    .uri("/internal/payment/orders/{orderId}/collected", orderId)
                    .header("X-Tenant-Context", token)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return Optional.of(new CollectedBreakdown(
                    node.path("cash").asLong(), node.path("wallet").asLong(), node.path("points").asLong()));
        } catch (Exception e) {
            // 按类注释的既定降级：退回"已收合计记现金"口径，不阻断账单读取。
            // 但本方法承载已收金额，故保留 DEBUG 以便区分"确实未收款"与"payment 调用失败"。
            log.debug("failed to read collected breakdown for orderId={}, falling back to total-as-cash", orderId, e);
            return Optional.empty();
        }
    }

    public record CollectedBreakdown(long cash, long wallet, long points) {}
}
