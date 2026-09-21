package com.gvchat.platform.admin.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import com.gvchat.platform.admin.api.ktv.PaymentSwitchConfig;
import com.gvchat.platform.admin.api.ktv.PricingPlan;
import com.gvchat.platform.admin.api.ktv.ServerCatalogItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KTV 配置领域服务真实客户端（RestClient）。
 * <p>
 *  - 计价方案：tenant 服务 /internal/pricing-plans（真实落库 tnt_pricing_plan）；
 *  - 服务人员目录：resource 服务 /internal/resources?resourceType=KTV_SERVER（真实查询 res_resource），
 *    catalog item 关联/单价/参与优惠字段占位（catalog 领域端点待补）；
 *  - 支付开关：tnt_store_payment_config 领域端点待补，当前返回默认（线上渠道默认关闭）。
 * </p>
 * 租户经 X-Tenant-Context 头转发（领域服务 TenantContextFilter 解析后 MyBatis 租户拦截器自动附加 tenant_id）。
 */
@Component
public class RestKtvConfigDomainClient implements KtvConfigDomainClient {

    private final RestClient tenantClient;
    private final RestClient resourceClient;
    private final RestClient paymentClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestKtvConfigDomainClient(
            @Value("${TENANT_SERVICE_BASE_URL:http://localhost:4110}") String tenantBaseUrl,
            @Value("${RESOURCE_SERVICE_BASE_URL:http://localhost:4120}") String resourceBaseUrl,
            @Value("${PAYMENT_SERVICE_BASE_URL:http://localhost:4140}") String paymentBaseUrl,
            InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        this.tenantClient = RestClient.builder().baseUrl(tenantBaseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
        this.resourceClient = RestClient.builder().baseUrl(resourceBaseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
        this.paymentClient = RestClient.builder().baseUrl(paymentBaseUrl)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    // —— 计价方案：tenant 服务（tnt_pricing_plan，按 store + resourceType 唯一） ——
    @Override
    public List<PricingPlan> listPricingPlans(Long storeId) {
        String uri = storeId == null ? "/internal/pricing-plans" : "/internal/pricing-plans?storeId=" + storeId;
        JsonNode node = getJson(tenantClient, uri);
        List<PricingPlan> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            // 同一门店的 KTV_ROOM（包厢价）与 KTV_SERVER（服务人员价）合成一条视图
            Map<Long, JsonNode> roomByStore = new LinkedHashMap<>();
            Map<Long, JsonNode> serverByStore = new LinkedHashMap<>();
            for (JsonNode item : node) {
                long id = item.path("storeId").asLong(0L);
                if ("KTV_SERVER".equalsIgnoreCase(item.path("resourceType").asText(""))) {
                    serverByStore.put(id, item);
                } else {
                    roomByStore.put(id, item);
                }
            }
            for (Map.Entry<Long, JsonNode> entry : roomByStore.entrySet()) {
                result.add(toPricingPlan(entry.getValue(), serverByStore.get(entry.getKey())));
            }
        }
        return result.isEmpty() ? List.of(defaultPricingPlan(storeId == null ? 1L : storeId)) : result;
    }

    /**
     * 保存门店计价方案：包厢价写 KTV_ROOM 行，服务人员价写 KTV_SERVER 行。
     * 金额一律用最小货币单位整数（前端用「元」输入并换算），tenant 侧按 (store, resourceType) upsert。
     */
    @Override
    public PricingPlan upsertPricingPlan(PricingPlan plan) {
        Long storeId = plan.storeId() == null ? 0L : plan.storeId();
        var roomNode = objectMapper.createObjectNode();
        roomNode.put("tenantId", tenantId());
        roomNode.put("storeId", storeId);
        roomNode.put("resourceType", "KTV_ROOM");
        roomNode.put("billingUnit", plan.billingUnit() == null ? "HOUR" : plan.billingUnit());
        roomNode.put("incrementMinutes", plan.incrementMinutes() == null ? 30 : plan.incrementMinutes());
        roomNode.put("roundingDirection", plan.roundingDirection() == null ? "CONSUMER_FAVOR" : plan.roundingDirection());
        roomNode.put("pricePerUnit", firstRoomPrice(plan));
        roomNode.put("defaultSessionMinutes", plan.defaultSessionMinutes() == null ? 120 : plan.defaultSessionMinutes());
        roomNode.put("overtimeRate", plan.overtimeRate() == null ? BigDecimal.ONE : BigDecimal.valueOf(plan.overtimeRate()));
        JsonNode roomSaved = postJson(tenantClient, "/internal/pricing-plans", roomNode);

        JsonNode serverSaved = null;
        if (plan.serverPricePerIncrement() != null) {
            var serverNode = objectMapper.createObjectNode();
            serverNode.put("tenantId", tenantId());
            serverNode.put("storeId", storeId);
            serverNode.put("resourceType", "KTV_SERVER");
            serverNode.put("billingUnit", plan.serverBillingUnit() == null ? "HOUR" : plan.serverBillingUnit());
            serverNode.put("incrementMinutes", plan.serverIncrementMinutes() == null ? 30 : plan.serverIncrementMinutes());
            serverNode.put("roundingDirection", plan.serverRoundingDirection() == null ? "CONSUMER_FAVOR" : plan.serverRoundingDirection());
            serverNode.put("pricePerUnit", plan.serverPricePerIncrement());
            serverNode.put("defaultSessionMinutes", plan.defaultSessionMinutes() == null ? 120 : plan.defaultSessionMinutes());
            serverNode.put("overtimeRate", plan.overtimeRate() == null ? BigDecimal.ONE : BigDecimal.valueOf(plan.overtimeRate()));
            serverSaved = postJson(tenantClient, "/internal/pricing-plans", serverNode);
        }
        return toPricingPlan(roomSaved, serverSaved);
    }

    // —— 支付开关：转发 payment 服务 pay_channel_config（线上渠道默认关闭） ——
    @Override
    public List<PaymentSwitchConfig> listPaymentSwitches(Long storeId) {
        JsonNode node = getJson(paymentClient, "/admin/payment-channels?tenantId=" + tenantId());
        List<PaymentSwitchConfig.PaymentChannelSwitch> channels = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                channels.add(toPaymentChannelSwitch(item));
            }
        }
        if (channels.isEmpty()) {
            channels = defaultPaymentSwitches(storeId == null ? 1L : storeId).channels();
        }
        return List.of(new PaymentSwitchConfig(1L, storeId, null, null, CurrencyResolver.currentCode(), channels));
    }

    @Override
    public PaymentSwitchConfig upsertPaymentSwitch(PaymentSwitchConfig config) {
        Long storeId = config.storeId();
        for (PaymentSwitchConfig.PaymentChannelSwitch ch : config.channels()) {
            if (ch == null || ch.channel() == null || ch.channel().isBlank()) continue;
            var body = objectMapper.createObjectNode();
            body.put("tenantId", tenantId());
            body.put("storeId", storeId == null ? 0L : storeId);
            body.put("channel", ch.channel());
            body.put("enabled", Boolean.TRUE.equals(ch.enabled()));
            body.put("merchantId", config.merchantAccountId() == null ? "" : String.valueOf(config.merchantAccountId()));
            postJson(paymentClient, "/admin/payment-channels", body);
        }
        return config;
    }

    private PaymentSwitchConfig.PaymentChannelSwitch toPaymentChannelSwitch(JsonNode item) {
        String channel = item.path("channel").asText("");
        String name = "ALIPAY".equals(channel) ? "支付宝" : "WECHAT".equals(channel) ? "微信支付" : "STRIPE".equals(channel) ? "Stripe" : channel;
        return new PaymentSwitchConfig.PaymentChannelSwitch(
                channel, name, item.path("enabled").asInt(0) == 1, false, null, null);
    }

    // —— 服务人员目录：resource 服务（KTV_SERVER） ——
    @Override
    public List<ServerCatalogItem> listServerCatalog(Long storeId) {
        String uri = "/internal/resources?resourceType=KTV_SERVER";
        if (storeId != null) {
            uri = uri + "&storeId=" + storeId;
        }
        JsonNode node = getJson(resourceClient, uri);
        List<ServerCatalogItem> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(toServerCatalogItem(item));
            }
        }
        return result.isEmpty() ? List.of(defaultServer(storeId == null ? 1L : storeId)) : result;
    }

    @Override
    public ServerCatalogItem upsertServerCatalogItem(ServerCatalogItem item) {
        var node = objectMapper.createObjectNode();
        node.put("tenantId", tenantId());
        node.put("storeId", item.storeId() == null ? 0L : item.storeId());
        node.put("resourceType", "KTV_SERVER");
        node.put("resourceCode", item.resourceCode());
        node.put("name", item.name());
        node.putNull("capacity");
        return toServerCatalogItem(postJson(resourceClient, "/admin/resources", node));
    }

    // —— 映射 ——
    private PricingPlan toPricingPlan(JsonNode item, JsonNode serverItem) {
        long price = item.path("pricePerUnit").asLong(0L);
        Integer incrementMinutes = item.path("incrementMinutes").asInt(30);
        String roundingDirection = item.path("roundingDirection").asText("CONSUMER_FAVOR");
        String billingUnit = item.path("billingUnit").asText("HOUR");
        long serverPrice = serverItem == null ? 0L : serverItem.path("pricePerUnit").asLong(0L);
        Integer serverIncrement = serverItem == null ? incrementMinutes : serverItem.path("incrementMinutes").asInt(30);
        String serverRounding = serverItem == null ? roundingDirection : serverItem.path("roundingDirection").asText("CONSUMER_FAVOR");
        return new PricingPlan(
                item.path("id").asLong(0L),
                item.path("storeId").asLong(0L),
                null,
                billingUnit,
                Map.of("标准", price),
                price,
                incrementMinutes,
                roundingDirection,
                0,
                item.path("overtimeRate").asDouble(1.0),
                item.path("defaultSessionMinutes").asInt(120),
                "CEIL_MINUTE",
                List.of(),
                false,
                serverItem == null ? billingUnit : serverItem.path("billingUnit").asText("HOUR"),
                serverIncrement,
                serverRounding,
                serverPrice);
    }

    private ServerCatalogItem toServerCatalogItem(JsonNode item) {
        return new ServerCatalogItem(
                item.path("id").asLong(0L),
                item.path("storeId").asLong(0L),
                null,
                item.path("resourceCode").asText(""),
                item.path("name").asText(""),
                item.path("status").asText("ENABLED"),
                null, // catalogItemId：catalog 领域端点待补
                "HOUR",
                30,
                "CONSUMER_FAVOR",
                null, // pricePerIncrement：catalog item 单价待补
                false);
    }

    /** 包厢单价（最小货币单位）：优先取显式 roomPricePerUnit，其次房型映射的第一项。 */
    private long firstRoomPrice(PricingPlan plan) {
        if (plan.roomPricePerUnit() != null) {
            return plan.roomPricePerUnit();
        }
        if (plan.unitPriceByRoomType() == null || plan.unitPriceByRoomType().isEmpty()) {
            return 0L;
        }
        return plan.unitPriceByRoomType().values().iterator().next();
    }

    private long tenantId() {
        TenantContext ctx = TenantContextHolder.get();
        return ctx == null ? 0L : ctx.tenantId();
    }

    // —— HTTP 帮助 ——
    private JsonNode getJson(RestClient client, String uri) {
        try {
            String resp = client.get()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson())
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(resp);
        } catch (RestClientResponseException e) {
            throw toApiException(e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用领域服务失败: " + uri, e);
        }
    }

    private JsonNode postJson(RestClient client, String uri, JsonNode body) {
        try {
            String resp = client.post()
                    .uri(uri)
                    .header("X-Tenant-Context", tenantContextJson())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(resp);
        } catch (RestClientResponseException e) {
            throw toApiException(e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用领域服务失败: " + uri, e);
        }
    }

    private String tenantContextJson() {
        String token = AdminContextHolder.get() == null ? null : AdminContextHolder.get().tenantContextToken();
        if (token == null || token.isBlank()) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择经营上下文");
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
        }
        return new ApiException(e.getStatusCode().value(), code, message);
    }

    // —— 默认兜底（领域服务无数据/不可达时的可配置默认） ——
    private PricingPlan defaultPricingPlan(Long storeId) {
        return new PricingPlan(
                1L, storeId, "星光 KTV · 朝阳店",
                "HOUR",
                Map.of("小包", 8000L, "中包", 12800L, "大包", 18800L),
                12800L, 30, "CONSUMER_FAVOR",
                0, 1.0, 120, "CEIL_MINUTE",
                List.of(new PricingPlan.PricingPackage(1L, "欢唱 3 小时", 180, 12800L, "ENABLED")),
                false, "HOUR", 30, "CONSUMER_FAVOR", 5000L);
    }

    private PaymentSwitchConfig defaultPaymentSwitches(Long storeId) {
        // 币种取当前上下文（缺省 USD，规范 §2）：骨架数据不得再写死旧默认币种。
        // 渠道级币种能力（微信/支付宝仅 CNY）由支付服务「可用支付方式」列表表达（规范 §2.2.3）。
        return new PaymentSwitchConfig(1L, storeId, "星光 KTV · 朝阳店", 1001L, CurrencyResolver.currentCode(),
                List.of(
                        new PaymentSwitchConfig.PaymentChannelSwitch("ALIPAY", "支付宝", false, false, 1L, 500000L),
                        new PaymentSwitchConfig.PaymentChannelSwitch("WECHAT", "微信支付", false, false, 1L, 500000L),
                        new PaymentSwitchConfig.PaymentChannelSwitch("STRIPE", "Stripe", false, false, 1L, 500000L)));
    }

    private ServerCatalogItem defaultServer(Long storeId) {
        return new ServerCatalogItem(1L, storeId, "星光 KTV · 朝阳店", "S01", "小雅",
                "ENABLED", 3001L, "HOUR", 30, "CONSUMER_FAVOR", 5000L, false);
    }
}
