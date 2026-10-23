package io.openware.platform.order.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 线上退款申请客户端：只提交 payment-service，不把 PENDING 当已退款。 */
@Component
public class HotelPaymentRefundClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HotelPaymentRefundClient(
            @Value("${app.payment-service.base-url:http://common-payment-service:4140}") String baseUrl,
            InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    /** 查询 payment-service 中同一退款申请的最新状态。 */
    public RefundState status(String requestId) {
        String token = TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank() || requestId == null) {
            return null;
        }
        try {
            String response = restClient.get()
                    .uri("/business/refund-requests")
                    .header("X-Tenant-Context", token)
                    .retrieve()
                    .body(String.class);
            JsonNode array = objectMapper.readTree(response);
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    if (requestId.equals(node.path("requestId").asText())) {
                        return new RefundState(node.path("status").asText(),
                                node.path("providerRefundNo").asText(""));
                    }
                }
            }
            return null;
        } catch (Exception exception) {
            throw new ApiException(502, "PAYMENT_REFUND_UNAVAILABLE", "查询 payment-service 退款状态失败");
        }
    }

    public record RefundState(String status, String providerRefundNo) {}

    public String requestRefund(Long orderId, long amountMinor, String reason, Long requestedBy) {        TenantContext context = TenantContextHolder.get();
        String token = TenantContextHolder.tokenOrNull();
        if (context == null || token == null || token.isBlank()) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文，不能提交线上退款");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "tenantId", context.tenantId(),
                    "storeId", context.storeId(),
                    "orderId", orderId,
                    "amount", amountMinor,
                    "reason", reason == null ? "酒店退款" : reason,
                    "requestedBy", requestedBy == null ? 0 : requestedBy));
            String response = restClient.post()
                    .uri("/business/refund-requests")
                    .header("X-Tenant-Context", token)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            String reference = node.path("requestId").asText("");
            if (reference.isBlank()) reference = node.path("id").asText("");
            if (reference.isBlank()) {
                throw new ApiException(502, "PAYMENT_REFUND_BAD_RESPONSE", "payment-service 未返回退款单号");
            }
            return reference;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(502, "PAYMENT_REFUND_UNAVAILABLE", "线上退款申请失败，请走现金退款或稍后重试");
        }
    }
}
