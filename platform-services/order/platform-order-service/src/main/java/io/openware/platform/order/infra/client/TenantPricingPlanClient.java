package io.openware.platform.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 读租户域的门店计价方案（内部只读 HTTP）：KTV 包厢/服务人员的价格由租户后台「计价方案」维护
 * （{@code tnt_pricing_plan}，按 tenant + store + resourceType 唯一），order 域不直接读它的表。
 *
 * <p>降级：租户服务不可达 / 未配置方案 / 上下文缺失时返回 empty，计费退回 application.yml 的
 * {@code ktv.pricing} 默认值，保证开台与结台不被外部依赖拖垮。
 *
 * <p>按房型定价：方案的 {@code unitPriceByRoomType}（房型编码 -&gt; 每计费单位单价）随方案一起解析，
 * 与资源侧房型字典单价一起参与取值（资源有房型单价时优先，其次方案按房型价，最后门店级单价）。
 */
@Component
public class TenantPricingPlanClient {
    private static final String SERVICE_NAME = "platform-order-service";
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";
    private static final String RESOURCE_TYPE_ROOM = "KTV_ROOM";
    private static final String RESOURCE_TYPE_SERVER = "KTV_SERVER";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String internalSecret;

    public TenantPricingPlanClient(@Value("${app.tenant-service.base-url:http://platform-tenant-service:4110}") String baseUrl,
                                   @Value("${app.internal-auth.secret:open-im-internal-dev-secret}") String internalSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalSecret = internalSecret;
    }

    /** 门店计价方案（包厢 + 服务人员两条，缺一条则对应字段为 null）。 */
    public Optional<TenantPricingPlan> findByStore(Long storeId) {
        String token = TenantContextHolder.tokenOrNull();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            long timestamp = System.currentTimeMillis();
            String resp = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/internal/pricing-plans");
                        if (storeId != null) {
                            builder.queryParam("storeId", storeId);
                        }
                        return builder.build();
                    })
                    .header("X-Tenant-Context", token)
                    .header(SOURCE_HEADER, SERVICE_NAME)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, simpleSignature(timestamp))
                    .retrieve()
                    .body(String.class);
            JsonNode list = objectMapper.readTree(resp);
            if (!list.isArray()) {
                return Optional.empty();
            }
            RoomPlan room = null;
            RoomPlan server = null;
            for (JsonNode node : list) {
                if (!"ACTIVE".equalsIgnoreCase(node.path("status").asText("ACTIVE"))) {
                    continue;
                }
                String type = node.path("resourceType").asText("");
                if (RESOURCE_TYPE_ROOM.equals(type) && room == null) {
                    room = toPlan(node);
                } else if (RESOURCE_TYPE_SERVER.equals(type) && server == null) {
                    server = toPlan(node);
                }
            }
            if (room == null) {
                return Optional.empty();
            }
            return Optional.of(new TenantPricingPlan(room, server));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private RoomPlan toPlan(JsonNode node) {
        String overtimeRate = node.path("overtimeRate").asText("1.0");
        return new RoomPlan(
                node.path("billingUnit").asText("HOUR"),
                node.path("pricePerUnit").asLong(0L),
                node.path("incrementMinutes").asInt(30),
                node.path("defaultSessionMinutes").asInt(120),
                new BigDecimal(overtimeRate.isBlank() ? "1.0" : overtimeRate),
                node.path("roundingDirection").asText("CONSUMER_FAVOR"),
                unitPriceByRoomType(node.path("unitPriceByRoomType")));
    }

    /**
     * 计价方案按房型定价映射（房型编码 -&gt; 每计费单位单价）。租户后台未开启按房型定价时该字段缺失，
     * 返回空映射（计费回退门店级单价），保证读取向前兼容、不因缺字段报错。
     */
    private Map<String, Long> unitPriceByRoomType(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> prices = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            long price = entry.getValue().asLong(0L);
            if (price > 0) {
                prices.put(entry.getKey(), price);
            }
        });
        return prices;
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

    /** 单条计价方案（resourceType 对应一行）。 */
    public record RoomPlan(String billingUnit, long pricePerUnit, int incrementMinutes,
                           int defaultSessionMinutes, BigDecimal overtimeRate, String roundingDirection,
                           Map<String, Long> unitPriceByRoomType) {}

    /** 门店计价方案：包厢为主，服务人员可选。 */
    public record TenantPricingPlan(RoomPlan room, RoomPlan server) {}
}
