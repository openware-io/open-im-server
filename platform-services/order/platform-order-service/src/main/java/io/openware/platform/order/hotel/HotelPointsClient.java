package io.openware.platform.order.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 酒店积分抵扣的 customer-service 内部客户端。
 *
 * <p>余额/换算失败一律 fail-closed（503），不能把“查不到余额”当成 0 积分继续下单；
 * 扣减失败必须让下单失败，避免出现“订单已建、积分未扣”。释放是补偿动作，失败只记录，
 * 由积分流水幂等键支持后续重新释放。
 */
@Component
@Slf4j
public class HotelPointsClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HotelPointsClient(
            @Value("${app.customer-service.base-url:http://platform-customer-service:4160}") String baseUrl,
            InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    /** 积分余额；服务不可用抛 503，调用方不得降级为 0。 */
    public long balance(Long customerId) {
        JsonNode node = get("/internal/customer/points/{customerId}", customerId);
        return node.path("availablePoints").asLong(0L);
    }

    /** 积分可抵扣金额（最小货币单位）。 */
    public long amountForPoints(long points) {
        JsonNode node = get("/internal/customer/points/points-to-amount/{points}", points);
        return node.path("amountMinor").asLong(0L);
    }

    /** 幂等扣减；失败抛异常，让下单事务回滚。 */
    public long redeem(Long customerId, long points, Long orderId, String idempotencyKey) {
        JsonNode node = post("/internal/customer/points/redeem",
                new RedeemRequest(customerId, points, orderId, idempotencyKey));
        return node.path("availablePoints").asLong(0L);
    }

    /** 下单失败补偿；尽力而为，不覆盖原始异常。 */
    public void release(Long customerId, long points, Long orderId, String idempotencyKey) {
        try {
            post("/internal/customer/points/release",
                    new RedeemRequest(customerId, points, orderId, "release:" + idempotencyKey));
        } catch (Exception e) {
            log.warn("failed to release hotel points customerId={} points={} orderId={}", customerId, points, orderId, e);
        }
    }

    private JsonNode get(String path, Object... uriVars) {
        try {
            String resp = restClient.get().uri(path, uriVars).header("X-Tenant-Context", token())
                    .retrieve().body(String.class);
            return parse(resp);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "HOTEL_POINTS_UNAVAILABLE", "积分服务暂时不可用");
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            String resp = restClient.post().uri(path).header("X-Tenant-Context", token())
                    .body(body).retrieve().body(String.class);
            return parse(resp);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "HOTEL_POINTS_UNAVAILABLE", "积分服务暂时不可用");
        }
    }

    private String token() {
        String token = TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank()) {
            throw new ApiException(503, "HOTEL_POINTS_UNAVAILABLE", "积分服务上下文不可用");
        }
        return token;
    }

    private JsonNode parse(String resp) throws Exception {
        if (resp == null) {
            throw new ApiException(503, "HOTEL_POINTS_UNAVAILABLE", "积分服务无响应");
        }
        return objectMapper.readTree(resp);
    }

    private record RedeemRequest(Long customerId, long points, Long orderId, String idempotencyKey) {}
}
