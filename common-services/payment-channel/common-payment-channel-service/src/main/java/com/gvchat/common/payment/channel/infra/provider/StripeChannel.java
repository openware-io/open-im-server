package com.gvchat.common.payment.channel.infra.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.payment.channel.infra.config.PayChannelProviderProperties;
import com.gvchat.common.payment.channel.infra.crypto.PaymentCryptoUtils;
import com.gvchat.common.payment.channel.infra.persistence.PaymentChannelTransactionRecorder;
import com.gvchat.common.payment.channel.spi.CallbackAck;
import com.gvchat.common.payment.channel.spi.ChannelCapability;
import com.gvchat.common.payment.channel.spi.CreatePaymentIntentRequest;
import com.gvchat.common.payment.channel.spi.CreatePaymentIntentResult;
import com.gvchat.common.payment.channel.spi.PaymentChannelProvider;
import com.gvchat.common.payment.channel.spi.QueryOrderRequest;
import com.gvchat.common.payment.channel.spi.QueryOrderResult;
import com.gvchat.common.payment.channel.spi.VerifyCallbackRequest;
import com.gvchat.common.payment.channel.spi.VerifyCallbackResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stripe 渠道适配：PaymentIntent 创建/查询（Bearer secret key）与 Webhook 验签。
 * Webhook：Stripe-Signature HMAC-SHA256 常量时间比对 + 时间戳容忍窗口 + event id 重放去重。
 */
@Slf4j
@Component
public class StripeChannel implements PaymentChannelProvider {

    /** 渠道调用失败时的对外载荷：固定中文 + code，不回显底层异常 message 等内部细节。 */
    private static final String CHANNEL_CALL_FAILED = "渠道调用失败(CHANNEL_CALL_FAILED)";

    private final PayChannelProviderProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentChannelTransactionRecorder recorder;

    public StripeChannel(PayChannelProviderProperties properties, PaymentChannelTransactionRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public ChannelCapability capability() {
        return new ChannelCapability("stripe", properties.getStripe().isEnabled());
    }

    @Override
    public CreatePaymentIntentResult createPaymentIntent(CreatePaymentIntentRequest request) {
        try {
            PayChannelProviderProperties.Stripe cfg = properties.getStripe();
            String currency = (request.currency() == null || request.currency().isBlank())
                    ? "usd" : request.currency().toLowerCase(Locale.ROOT);
            String body = "amount=" + request.amount()
                    + "&currency=" + urlEncode(currency)
                    + "&metadata[tenant_id]=" + urlEncode(String.valueOf(request.tenantId() == null ? 0L : request.tenantId()))
                    + "&metadata[order_id]=" + urlEncode(request.orderId() == null ? "" : request.orderId());

            String response = restClient.post()
                    .uri(cfg.getApiUrl() + "/payment_intents")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getSecretKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> json = fromJson(response);
            return new CreatePaymentIntentResult("stripe",
                    str(json, "id"),
                    mapStatus(str(json, "status")),
                    str(json, "client_secret"),
                    response);
        } catch (Exception e) {
            // 对外只给固定中文 + code；底层异常 message 可能泄露网关/证书配置线索，只进日志。
            log.error("Stripe 下单调用失败: orderId={}", request.orderId(), e);
            return new CreatePaymentIntentResult("stripe", "", "FAILED", "", CHANNEL_CALL_FAILED);
        }
    }

    @Override
    public VerifyCallbackResult verifyCallback(VerifyCallbackRequest request) {
        try {
            PayChannelProviderProperties.Stripe cfg = properties.getStripe();
            if (!verifyWebhookSignature(request.rawBody(), resolveSignature(request),
                    cfg.getWebhookSecret(), cfg.getWebhookToleranceSeconds())) {
                // 验签失败必须可观测；只记渠道与原因，不打印密钥与明文报文（订单号在验签前不可信，故不记录）。
                log.warn("Stripe 回调验签失败: 签名不匹配或时间戳超出容忍窗口");
                return fail(request);
            }
            Map<String, Object> json = fromJson(request.rawBody());
            String eventId = str(json, "id");
            String eventType = str(json, "type");
            Map<String, Object> data = asMap(json.get("data"));
            Map<String, Object> object = asMap(data.get("object"));
            Map<String, Object> metadata = asMap(object.get("metadata"));

            String transactionId = str(object, "id");
            String orderId = str(metadata, "order_id");
            long amount = toLong(object.get("amount"));
            String currency = str(object, "currency");
            String status = mapStatus(str(object, "status"));
            long tenantId = toLong(metadata.get("tenant_id"));

            // 仅记录 payment_intent 事件；event id 重放去重，payment_intent id 作为渠道交易号落库
            if (isPaymentIntentEvent(eventType) && notBlank(transactionId)
                    && (isBlank(eventId) || !recorder.existsByEventId("stripe", eventId))) {
                recorder.record("stripe", transactionId, eventId, tenantId, orderId, amount, currency, status, request.rawBody());
            }
            return new VerifyCallbackResult(true, transactionId, orderId, amount, currency, status, request.rawBody());
        } catch (Exception e) {
            // 验签/解析/落库失败必须留痕（不打印密钥与明文报文）。
            log.warn("Stripe 回调处理失败（验签/解析/落库）", e);
            return fail(request);
        }
    }

    @Override
    public QueryOrderResult queryOrder(QueryOrderRequest request) {
        try {
            PayChannelProviderProperties.Stripe cfg = properties.getStripe();
            String id = request.transactionId() == null ? "" : request.transactionId();
            String response = restClient.get()
                    .uri(cfg.getApiUrl() + "/payment_intents/" + urlEncode(id))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getSecretKey())
                    .retrieve()
                    .body(String.class);

            Map<String, Object> json = fromJson(response);
            Map<String, Object> metadata = asMap(json.get("metadata"));
            return new QueryOrderResult("stripe",
                    str(json, "id"),
                    str(metadata, "order_id"),
                    mapStatus(str(json, "status")),
                    toLong(json.get("amount")),
                    str(json, "currency"));
        } catch (Exception e) {
            return new QueryOrderResult("stripe", "", request.orderId(), "FAILED", 0L, "usd");
        }
    }

    @Override
    public CallbackAck callbackAck(VerifyCallbackResult result) {
        if (result.verified()) {
            return CallbackAck.ok("text/plain;charset=utf-8", "");
        }
        return CallbackAck.fail("text/plain;charset=utf-8", "signature verification failed");
    }

    private static VerifyCallbackResult fail(VerifyCallbackRequest request) {
        return new VerifyCallbackResult(false, null, null, 0L, null, "FAILED", request.rawBody());
    }

    /**
     * Stripe webhook 验签：HMAC-SHA256(timestamp.payload) 与 Stripe-Signature 头中的任一 v1 常量时间比对，
     * 并校验时间戳在容忍窗口内（防旧签名重放）。密钥由环境注入。
     */
    private boolean verifyWebhookSignature(String rawBody, String signatureHeader, String secret, long toleranceSeconds) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || secret == null || secret.isBlank()) {
            return false;
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                if ("t".equals(key)) {
                    timestamp = value;
                } else if ("v1".equals(key)) {
                    signatures.add(value);
                }
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        try {
            long eventTime = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000L;
            if (Math.abs(now - eventTime) > toleranceSeconds) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = PaymentCryptoUtils.hmacSha256Hex(timestamp + "." + rawBody, secret);
        for (String v1 : signatures) {
            if (PaymentCryptoUtils.constantTimeEquals(expected, v1)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveSignature(VerifyCallbackRequest request) {
        Map<String, String> headers = request.headers();
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("stripe-signature".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isPaymentIntentEvent(String eventType) {
        return eventType != null && eventType.startsWith("payment_intent.");
    }

    private static String mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> "SUCCEEDED";
            case "canceled" -> "CLOSED";
            case "requires_payment_method", "requires_confirmation", "requires_action", "processing" -> "PROCESSING";
            default -> "PROCESSING";
        };
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Stripe 响应 JSON 解析失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.<String, Object>of();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
