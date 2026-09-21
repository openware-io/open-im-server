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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 支付宝渠道适配：当面付预下单（alipay.trade.precreate）/ 交易查询（alipay.trade.query）/ 异步通知 RSA2 验签。
 * 异步通知：RSA2 验签 + app_id 校验 + notify_id 重放去重 + trade_no 渠道交易号落库，幂等返回 success。
 */
@Slf4j
@Component
public class AlipayChannel implements PaymentChannelProvider {

    private static final String METHOD_PRECREATE = "alipay.trade.precreate";
    private static final String METHOD_QUERY = "alipay.trade.query";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PayChannelProviderProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentChannelTransactionRecorder recorder;

    public AlipayChannel(PayChannelProviderProperties properties, PaymentChannelTransactionRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String provider() {
        return "alipay";
    }

    @Override
    public ChannelCapability capability() {
        return new ChannelCapability("alipay", properties.getAlipay().isEnabled());
    }

    @Override
    public CreatePaymentIntentResult createPaymentIntent(CreatePaymentIntentRequest request) {
        PayChannelProviderProperties.Alipay cfg = properties.getAlipay();
        Map<String, String> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", request.orderId());
        biz.put("total_amount", toYuan(request.amount()));
        biz.put("subject", "gvchat-order-" + request.orderId());
        biz.put("passback_params", String.valueOf(request.tenantId() == null ? 0L : request.tenantId()));

        Map<String, String> params = baseParams(cfg, METHOD_PRECREATE);
        params.put("biz_content", toJson(biz));
        params.put("sign", PaymentCryptoUtils.rsa2Sign(buildSignContent(params, false), cfg.getMerchantPrivateKey()));

        String response = restClient.post()
                .uri(cfg.getGatewayUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formEncode(params))
                .retrieve()
                .body(String.class);

        Map<String, Object> json = fromJson(response);
        Map<String, Object> resp = asMap(json.get("alipay_trade_precreate_response"));
        String status = "10000".equals(str(resp, "code")) ? "PROCESSING" : "FAILED";
        return new CreatePaymentIntentResult("alipay", str(resp, "out_trade_no"), status, str(resp, "qr_code"), response);
    }

    @Override
    public VerifyCallbackResult verifyCallback(VerifyCallbackRequest request) {
        try {
            PayChannelProviderProperties.Alipay cfg = properties.getAlipay();
            Map<String, String> params = parseForm(request.rawBody());
            String sign = params.get("sign");
            if (sign == null || sign.isBlank()) {
                log.warn("支付宝回调验签失败: orderId={} reason=缺少 sign",
                        params.getOrDefault("out_trade_no", ""));
                return fail(request);
            }
            // 防跨商户/跨应用重放：通知 app_id 必须与配置一致
            if (!cfg.getAppId().equals(params.getOrDefault("app_id", ""))) {
                log.warn("支付宝回调验签失败: orderId={} reason=app_id 与配置不一致",
                        params.getOrDefault("out_trade_no", ""));
                return fail(request);
            }
            // 异步通知验签：除去 sign、sign_type 后字典序拼接，RSA2（SHA256withRSA）验签
            boolean verified = PaymentCryptoUtils.rsa2Verify(
                    buildSignContent(params, true), sign, cfg.getAlipayPublicKey());
            if (!verified) {
                log.warn("支付宝回调验签失败: orderId={} reason=签名校验不通过",
                        params.getOrDefault("out_trade_no", ""));
                return fail(request);
            }
            String transactionId = params.getOrDefault("trade_no", "");
            String notifyId = params.getOrDefault("notify_id", "");
            String orderId = params.getOrDefault("out_trade_no", "");
            long amount = toCents(params.getOrDefault("total_amount", "0"));
            String currency = defaultCurrency(params.getOrDefault("currency", "CNY"));
            String status = mapTradeStatus(params.getOrDefault("trade_status", ""));
            long tenantId = parseLong(params.getOrDefault("passback_params", "0"));

            // 幂等：以 notify_id 去重重放，trade_no 作为渠道交易号落库（同一交易不同状态的通知也会按 notify_id 区分）
            if (notBlank(transactionId) && (isBlank(notifyId) || !recorder.existsByEventId("alipay", notifyId))) {
                recorder.record("alipay", transactionId, notifyId, tenantId, orderId, amount, currency, status, request.rawBody());
            }
            return new VerifyCallbackResult(true, transactionId, orderId, amount, currency, status, request.rawBody());
        } catch (Exception e) {
            // 验签/解析/落库失败必须留痕（不打印密钥与明文报文）。
            log.warn("支付宝回调处理失败（验签/解析/落库）", e);
            return fail(request);
        }
    }

    @Override
    public QueryOrderResult queryOrder(QueryOrderRequest request) {
        PayChannelProviderProperties.Alipay cfg = properties.getAlipay();
        Map<String, String> biz = new LinkedHashMap<>();
        if (notBlank(request.orderId())) {
            biz.put("out_trade_no", request.orderId());
        }
        if (notBlank(request.transactionId())) {
            biz.put("trade_no", request.transactionId());
        }

        Map<String, String> params = baseParams(cfg, METHOD_QUERY);
        params.put("biz_content", toJson(biz));
        params.put("sign", PaymentCryptoUtils.rsa2Sign(buildSignContent(params, false), cfg.getMerchantPrivateKey()));

        String response = restClient.post()
                .uri(cfg.getGatewayUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formEncode(params))
                .retrieve()
                .body(String.class);

        Map<String, Object> json = fromJson(response);
        Map<String, Object> resp = asMap(json.get("alipay_trade_query_response"));
        return new QueryOrderResult("alipay",
                str(resp, "trade_no"),
                request.orderId(),
                mapTradeStatus(str(resp, "trade_status")),
                toCents(str(resp, "total_amount")),
                defaultCurrency("CNY"));
    }

    @Override
    public CallbackAck callbackAck(VerifyCallbackResult result) {
        return CallbackAck.ok("text/plain;charset=utf-8", result.verified() ? "success" : "fail");
    }

    private static VerifyCallbackResult fail(VerifyCallbackRequest request) {
        return new VerifyCallbackResult(false, null, null, 0L, null, "FAILED", request.rawBody());
    }

    private Map<String, String> baseParams(PayChannelProviderProperties.Alipay cfg, String method) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", cfg.getAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", TIMESTAMP_FORMAT.format(LocalDateTime.now()));
        params.put("version", "1.0");
        if (METHOD_PRECREATE.equals(method)) {
            params.put("notify_url", cfg.getNotifyUrl());
        }
        return params;
    }

    /** 签名内容：字典序拼接 k=v&...，跳过 sign 与空值；excludeSignType=true 时用于异步通知验签（sign_type 不参与）。 */
    private static String buildSignContent(Map<String, String> params, boolean excludeSignType) {
        List<String> keys = new ArrayList<>(params.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if ("sign".equals(k)) {
                continue;
            }
            if (excludeSignType && "sign_type".equals(k)) {
                continue;
            }
            String v = params.get(k);
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(k)).append('=').append(urlEncode(v == null ? "" : v));
        });
        return sb.toString();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            String k = idx < 0 ? pair : pair.substring(0, idx);
            String v = idx < 0 ? "" : pair.substring(idx + 1);
            map.put(urlDecode(k), urlDecode(v));
        }
        return map;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static String mapTradeStatus(String tradeStatus) {
        return switch (tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
            case "WAIT_BUYER_PAY" -> "PROCESSING";
            case "TRADE_CLOSED" -> "CLOSED";
            default -> "PROCESSING";
        };
    }

    private static String toYuan(long amount) {
        return BigDecimal.valueOf(amount, 2).toPlainString();
    }

    private static long toCents(String yuan) {
        try {
            return new BigDecimal(yuan).movePointRight(2).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("支付宝请求 JSON 序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("支付宝响应 JSON 解析失败", e);
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

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? "CNY" : currency.toUpperCase(Locale.ROOT);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
