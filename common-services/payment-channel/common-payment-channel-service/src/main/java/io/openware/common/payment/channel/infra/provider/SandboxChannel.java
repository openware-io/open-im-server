package io.openware.common.payment.channel.infra.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.payment.channel.infra.config.PayChannelProviderProperties;
import io.openware.common.payment.channel.infra.crypto.PaymentCryptoUtils;
import io.openware.common.payment.channel.infra.persistence.PaymentChannelTransactionRecorder;
import io.openware.common.payment.channel.spi.CallbackAck;
import io.openware.common.payment.channel.spi.ChannelCapability;
import io.openware.common.payment.channel.spi.CreatePaymentIntentRequest;
import io.openware.common.payment.channel.spi.CreatePaymentIntentResult;
import io.openware.common.payment.channel.spi.PaymentChannelProvider;
import io.openware.common.payment.channel.spi.QueryOrderRequest;
import io.openware.common.payment.channel.spi.QueryOrderResult;
import io.openware.common.payment.channel.spi.VerifyCallbackRequest;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import io.openware.infrastructure.currency.Currency;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SANDBOX 渠道适配：**仅本地 Kind E2E** 使用的可控回调源（默认关闭）。
 *
 * <p>存在理由：本地/CI 的 Kind 没有真实商户密钥，Alipay/Wechat/Stripe 默认 disabled，真实回调无法
 * 在 E2E 中产生资金事实。SANDBOX 用固定 HMAC-SHA256 对回调报文验签，但**走与其他渠道完全相同的**
 * 链路：回调验签 → {@code pay_channel_transaction} 落库 → payment-service 内部结算 →
 * {@code pay_intent/pay_transaction/ord_order} 落账。因此成功、失败、重复、退款回调都能被真实 E2E 验证。
 *
 * <p>安全边界：仅当 {@code pay-channel-provider.sandbox.enabled=true}（本地 k8s manifest 显式注入）时
 * 才接受回调；共享/生产环境不得启用。回调体不落任何生产密钥，验签失败一律不改变资金事实。
 *
 * <h2>回调契约（E2E 用）</h2>
 * <pre>
 * POST /internal/channels/sandbox/callback
 * Header: X-Sandbox-Signature: hex(HMAC-SHA256(rawBody, sandbox.webhook-secret))
 * Body (JSON):
 * {
 *   "transactionId": "E2E-SANDBOX-TX-1",   // 必填，渠道交易号（结算幂等键）
 *   "eventId": "E2E-SANDBOX-EVT-1",        // 可选，回调事件 ID（重放追溯）
 *   "orderId": "9001",                     // 必填，业务订单号（数字字符串）
 *   "amount": 8000,                        // 必填，最小货币单位整数
 *   "currency": "CNY",                     // 可选，缺省按订单币种
 *   "status": "SUCCEEDED",                 // SUCCEEDED/REFUNDED/FAILED/CLOSED
 *   "tenantId": 100                        // 可选，携带时必须与订单租户一致
 * }
 * </pre>
 */
@Slf4j
@Component
public class SandboxChannel implements PaymentChannelProvider {

    /** 回调签名头：hex(HMAC-SHA256(rawBody, webhookSecret))。 */
    public static final String SIGNATURE_HEADER = "X-Sandbox-Signature";
    private static final String PROVIDER = "sandbox";

    private final PayChannelProviderProperties properties;
    private final PaymentChannelTransactionRecorder recorder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SandboxChannel(PayChannelProviderProperties properties, PaymentChannelTransactionRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ChannelCapability capability() {
        return new ChannelCapability(PROVIDER, properties.getSandbox().isEnabled());
    }

    /** 本地回显下单结果：E2E 不依赖真实渠道下单，只验证回调结算链路。 */
    @Override
    public CreatePaymentIntentResult createPaymentIntent(CreatePaymentIntentRequest request) {
        return new CreatePaymentIntentResult(PROVIDER, request.orderId(), "PROCESSING",
                "sandbox://" + (request.orderId() == null ? "" : request.orderId()), "{}");
    }

    @Override
    public VerifyCallbackResult verifyCallback(VerifyCallbackRequest request) {
        try {
            PayChannelProviderProperties.Sandbox cfg = properties.getSandbox();
            if (!cfg.isEnabled()) {
                log.warn("SANDBOX 渠道未启用，拒绝回调（仅本地 E2E 允许启用）");
                return fail(request);
            }
            String secret = cfg.getWebhookSecret();
            String signature = header(request.headers(), SIGNATURE_HEADER);
            if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
                log.warn("SANDBOX 回调验签失败: reason={}", secret == null || secret.isBlank()
                        ? "未配置 webhook secret" : "缺少 " + SIGNATURE_HEADER + " 头");
                return fail(request);
            }
            // 常量时间比对：与真实渠道一致的验签口径，签名不匹配绝不触碰资金事实。
            String expected = PaymentCryptoUtils.hmacSha256Hex(request.rawBody(), secret);
            if (!PaymentCryptoUtils.constantTimeEquals(expected, signature.trim())) {
                log.warn("SANDBOX 回调验签失败: reason=签名不匹配");
                return fail(request);
            }
            Map<String, Object> payload = fromJson(request.rawBody());
            String transactionId = str(payload, "transactionId");
            String orderId = str(payload, "orderId");
            String status = str(payload, "status");
            if (transactionId.isEmpty() || orderId.isEmpty() || status.isEmpty()) {
                log.warn("SANDBOX 回调缺少必填字段: transactionId/orderId/status 均不能为空");
                return fail(request);
            }
            String eventId = blankToNull(str(payload, "eventId"));
            long amount = toLong(payload.get("amount"));
            String currency = defaultCurrency(str(payload, "currency"));
            long tenantId = toLong(payload.get("tenantId"));

            // 与其他渠道同口径：先落渠道交易（幂等），再由 controller 触发 payment-service 结算。
            if (eventId == null || !recorder.existsByEventId(PROVIDER, eventId)) {
                recorder.record(PROVIDER, transactionId, eventId, tenantId, orderId, amount, currency, status,
                        request.rawBody());
            }
            return new VerifyCallbackResult(true, transactionId, orderId, amount, currency,
                    status.toUpperCase(Locale.ROOT), request.rawBody(), eventId, tenantId);
        } catch (RuntimeException e) {
            // 解析/落库失败必须留痕，且不得把未验签的报文当成功。
            log.warn("SANDBOX 回调处理失败（解析/落库）", e);
            return fail(request);
        }
    }

    @Override
    public QueryOrderResult queryOrder(QueryOrderRequest request) {
        // SANDBOX 不维护订单状态：查询回显 PROCESSING，真实状态以回调结算台账为准。
        return new QueryOrderResult(PROVIDER, request.transactionId(), request.orderId(), "PROCESSING", 0L,
                Currency.DEFAULT.code());
    }

    @Override
    public CallbackAck callbackAck(VerifyCallbackResult result) {
        if (result.verified()) {
            return CallbackAck.ok("application/json;charset=utf-8", "{\"code\":\"SUCCESS\"}");
        }
        return CallbackAck.fail("application/json;charset=utf-8", "{\"code\":\"FAIL\"}");
    }

    private static VerifyCallbackResult fail(VerifyCallbackRequest request) {
        return new VerifyCallbackResult(false, null, null, 0L, null, "FAILED", request.rawBody(), null, null);
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("SANDBOX 回调 JSON 解析失败", e);
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
            // 金额/租户解析失败按 0 处理属资金相关，必须留痕而不是完全静默。
            log.warn("SANDBOX 数字字段不是数字，按 0 处理: raw={}", value, e);
            return 0L;
        }
    }

    private static String defaultCurrency(String currency) {
        return currency == null || currency.isBlank() ? Currency.DEFAULT.code() : currency.toUpperCase(Locale.ROOT);
    }
}
