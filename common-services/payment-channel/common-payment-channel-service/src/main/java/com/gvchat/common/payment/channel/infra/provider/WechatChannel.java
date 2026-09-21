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
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付渠道适配：
 * v2（默认，XML 统一下单/查单 + MD5/HMAC-SHA256 验签）与 v3（JSON 统一下单/查单 + 平台证书 RSA 验签 + AES-256-GCM 解密）。
 * 密钥与证书全部来自 pay-channel-provider.wechat.* 环境占位，禁止硬编码。
 */
@Slf4j
@Component
public class WechatChannel implements PaymentChannelProvider {

    private static final String API_V3 = "v3";
    private static final String V2_UNIFIED_ORDER_URL = "https://api.mch.weixin.qq.com/pay/unifiedorder";
    private static final String V2_ORDER_QUERY_URL = "https://api.mch.weixin.qq.com/pay/orderquery";
    private static final String V3_BASE = "https://api.mch.weixin.qq.com";
    private static final String V3_TRANSACTIONS_NATIVE = "/v3/pay/transactions/native";
    private static final String V3_AUTH_SCHEME = "WECHATPAY2-SHA256-RSA2048";
    private static final String V3_SUCCESS_EVENT = "TRANSACTION.SUCCESS";
    private static final String DEFAULT_SIGN_TYPE = "MD5";
    /** 渠道调用失败时的对外载荷：固定中文 + code，不回显底层异常 message 等内部细节。 */
    private static final String CHANNEL_CALL_FAILED = "渠道调用失败(CHANNEL_CALL_FAILED)";

    private final PayChannelProviderProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentChannelTransactionRecorder recorder;

    public WechatChannel(PayChannelProviderProperties properties, PaymentChannelTransactionRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String provider() {
        return "wechat";
    }

    @Override
    public ChannelCapability capability() {
        return new ChannelCapability("wechat", properties.getWechat().isEnabled());
    }

    private boolean isV3() {
        return API_V3.equalsIgnoreCase(properties.getWechat().getApiVersion());
    }

    @Override
    public CreatePaymentIntentResult createPaymentIntent(CreatePaymentIntentRequest request) {
        return isV3() ? createV3(request) : createV2(request);
    }

    @Override
    public VerifyCallbackResult verifyCallback(VerifyCallbackRequest request) {
        return isV3() ? verifyV3(request) : verifyV2(request);
    }

    @Override
    public QueryOrderResult queryOrder(QueryOrderRequest request) {
        return isV3() ? queryV3(request) : queryV2(request);
    }

    @Override
    public CallbackAck callbackAck(VerifyCallbackResult result) {
        if (!isV3()) {
            String code = result.verified() ? "SUCCESS" : "FAIL";
            String msg = result.verified() ? "OK" : "SIGN_ERROR";
            return CallbackAck.ok("application/xml;charset=utf-8",
                    "<xml><return_code><![CDATA[" + code + "]]></return_code><return_msg><![CDATA[" + msg + "]]></return_msg></xml>");
        }
        if (result.verified()) {
            return CallbackAck.ok("application/json;charset=utf-8", "{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
        }
        return CallbackAck.fail("application/json;charset=utf-8", "{\"code\":\"FAIL\",\"message\":\"验签失败\"}");
    }

    // ---------------- v2 ----------------

    private CreatePaymentIntentResult createV2(CreatePaymentIntentRequest request) {
        try {
            PayChannelProviderProperties.Wechat cfg = properties.getWechat();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("appid", cfg.getAppId());
            params.put("mch_id", cfg.getMerchantId());
            params.put("nonce_str", randomNonce());
            params.put("body", "gvchat-order-" + request.orderId());
            params.put("out_trade_no", request.orderId());
            params.put("total_fee", String.valueOf(request.amount()));
            params.put("spbill_create_ip", "127.0.0.1");
            params.put("notify_url", cfg.getNotifyUrl());
            params.put("trade_type", "NATIVE");
            params.put("sign_type", DEFAULT_SIGN_TYPE);
            params.put("attach", String.valueOf(request.tenantId() == null ? 0L : request.tenantId()));
            params.put("sign", sign(params, DEFAULT_SIGN_TYPE));

            String response = restClient.post()
                    .uri(V2_UNIFIED_ORDER_URL)
                    .contentType(MediaType.TEXT_XML)
                    .body(buildXml(params))
                    .retrieve()
                    .body(String.class);

            Map<String, String> resp = xmlToMap(response);
            String status = "FAILED";
            String transactionId = "";
            String clientSecret = "";
            if ("SUCCESS".equals(resp.get("return_code")) && "SUCCESS".equals(resp.get("result_code"))) {
                status = "PROCESSING";
                transactionId = resp.getOrDefault("prepay_id", "");
                clientSecret = resp.getOrDefault("code_url", "");
            }
            return new CreatePaymentIntentResult("wechat", transactionId, status, clientSecret, response);
        } catch (Exception e) {
            // 对外只给固定中文 + code；底层异常 message 可能泄露网关/证书配置线索，只进日志。
            log.error("微信支付 V2 下单调用失败: orderId={}", request.orderId(), e);
            return new CreatePaymentIntentResult("wechat", "", "FAILED", "", CHANNEL_CALL_FAILED);
        }
    }

    private VerifyCallbackResult verifyV2(VerifyCallbackRequest request) {
        try {
            Map<String, String> params = xmlToMap(request.rawBody());
            String sign = params.get("sign");
            String signType = params.getOrDefault("sign_type", DEFAULT_SIGN_TYPE);
            String computed = sign(params, signType);
            if (sign == null || !PaymentCryptoUtils.constantTimeEquals(sign, computed)) {
                // 验签失败必须可观测（只记渠道/订单号/原因，不打印密钥与明文报文）。
                log.warn("微信支付回调验签失败(V2): orderId={} signType={} reason={}",
                        params.getOrDefault("out_trade_no", ""), signType,
                        sign == null ? "缺少 sign" : "签名不匹配");
                return fail(request);
            }
            String transactionId = params.getOrDefault("transaction_id", "");
            String orderId = params.getOrDefault("out_trade_no", "");
            long amount = parseLong(params.getOrDefault("total_fee", "0"));
            String currency = defaultCurrency(params.getOrDefault("fee_type", "CNY"));
            String status = mapTradeState(params.getOrDefault("trade_state", ""));
            long tenantId = parseLong(params.getOrDefault("attach", "0"));

            if (!transactionId.isEmpty() && !recorder.exists("wechat", transactionId)) {
                recorder.record("wechat", transactionId, tenantId, orderId, amount, currency, status, request.rawBody());
            }
            return new VerifyCallbackResult(true, transactionId, orderId, amount, currency, status, request.rawBody());
        } catch (Exception e) {
            // 解析/验签/落库任何一步失败都要留痕，否则「回调被静默判失败」无法排查。
            log.warn("微信支付 V2 回调处理失败（验签/解析/落库）", e);
            return fail(request);
        }
    }

    private QueryOrderResult queryV2(QueryOrderRequest request) {
        try {
            PayChannelProviderProperties.Wechat cfg = properties.getWechat();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("appid", cfg.getAppId());
            params.put("mch_id", cfg.getMerchantId());
            params.put("nonce_str", randomNonce());
            if (notBlank(request.transactionId())) {
                params.put("transaction_id", request.transactionId());
            }
            if (notBlank(request.orderId())) {
                params.put("out_trade_no", request.orderId());
            }
            params.put("sign_type", DEFAULT_SIGN_TYPE);
            params.put("sign", sign(params, DEFAULT_SIGN_TYPE));

            String response = restClient.post()
                    .uri(V2_ORDER_QUERY_URL)
                    .contentType(MediaType.TEXT_XML)
                    .body(buildXml(params))
                    .retrieve()
                    .body(String.class);

            Map<String, String> resp = xmlToMap(response);
            String status = "FAILED";
            if ("SUCCESS".equals(resp.get("return_code")) && "SUCCESS".equals(resp.get("result_code"))) {
                status = mapTradeState(resp.getOrDefault("trade_state", ""));
            }
            return new QueryOrderResult("wechat",
                    resp.getOrDefault("transaction_id", ""),
                    resp.getOrDefault("out_trade_no", request.orderId()),
                    status,
                    parseLong(resp.getOrDefault("total_fee", "0")),
                    defaultCurrency(resp.getOrDefault("fee_type", "CNY")));
        } catch (Exception e) {
            return new QueryOrderResult("wechat", "", request.orderId(), "FAILED", 0L, "CNY");
        }
    }

    /** 微信 v2 签名：字典序拼接 key=value&...&key=secretKey，MD5 或 HMAC-SHA256 取大写十六进制。 */
    private String sign(Map<String, String> params, String signType) {
        params.remove("sign");
        String key = properties.getWechat().getSecretKey();
        StringBuilder sb = new StringBuilder();
        params.keySet().stream().sorted().forEach(k -> {
            String v = params.get(k);
            if (v != null && !v.isEmpty()) {
                sb.append(k).append('=').append(v).append('&');
            }
        });
        sb.append("key=").append(key == null ? "" : key);
        if ("HMAC-SHA256".equalsIgnoreCase(signType)) {
            return PaymentCryptoUtils.hmacSha256Hex(sb.toString(), key == null ? "" : key).toUpperCase(Locale.ROOT);
        }
        return PaymentCryptoUtils.md5Hex(sb.toString()).toUpperCase(Locale.ROOT);
    }

    // ---------------- v3 ----------------

    private CreatePaymentIntentResult createV3(CreatePaymentIntentRequest request) {
        try {
            PayChannelProviderProperties.Wechat cfg = properties.getWechat();
            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put("total", request.amount());
            amount.put("currency", defaultCurrency(request.currency()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("appid", cfg.getAppId());
            body.put("mchid", cfg.getMerchantId());
            body.put("description", "gvchat-order-" + request.orderId());
            body.put("out_trade_no", request.orderId());
            body.put("notify_url", cfg.getNotifyUrl());
            body.put("amount", amount);
            body.put("attach", String.valueOf(request.tenantId() == null ? 0L : request.tenantId()));

            String jsonBody = toJson(body);
            String response = restClient.post()
                    .uri(V3_BASE + V3_TRANSACTIONS_NATIVE)
                    .header(HttpHeaders.AUTHORIZATION, v3Authorization("POST", V3_TRANSACTIONS_NATIVE, jsonBody))
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> resp = fromJson(response);
            return new CreatePaymentIntentResult("wechat", str(resp, "out_trade_no"), "PROCESSING", str(resp, "code_url"), response);
        } catch (Exception e) {
            // 对外只给固定中文 + code；底层异常 message 可能泄露网关/证书配置线索，只进日志。
            log.error("微信支付 V3 下单调用失败: orderId={}", request.orderId(), e);
            return new CreatePaymentIntentResult("wechat", "", "FAILED", "", CHANNEL_CALL_FAILED);
        }
    }

    private VerifyCallbackResult verifyV3(VerifyCallbackRequest request) {
        try {
            PayChannelProviderProperties.Wechat cfg = properties.getWechat();
            Map<String, String> headers = request.headers() == null ? Map.of() : request.headers();
            String timestamp = header(headers, "wechatpay-timestamp");
            String nonce = header(headers, "wechatpay-nonce");
            String signature = header(headers, "wechatpay-signature");
            String serial = header(headers, "wechatpay-serial");
            if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature) || isBlank(request.rawBody())) {
                log.warn("微信支付回调验签失败(V3): 缺少验签所需头或报文为空");
                return fail(request);
            }
            // 配置平台证书序列号后校验 Wechatpay-Serial，防证书错配重放
            if (notBlank(cfg.getPlatformSerialNo()) && !cfg.getPlatformSerialNo().equals(serial)) {
                log.warn("微信支付回调验签失败(V3): 平台证书序列号与配置不一致");
                return fail(request);
            }
            // 验签内容：timestamp\nnonce\nbody\n，使用平台证书公钥 SHA256withRSA
            String message = timestamp + "\n" + nonce + "\n" + request.rawBody() + "\n";
            if (!PaymentCryptoUtils.rsa2Verify(message, signature, cfg.getPlatformPublicKey())) {
                log.warn("微信支付回调验签失败(V3): 签名校验不通过");
                return fail(request);
            }
            Map<String, Object> json = fromJson(request.rawBody());
            String eventType = str(json, "event_type");
            String eventId = str(json, "id");
            Map<String, Object> resource = asMap(json.get("resource"));
            String decrypted = PaymentCryptoUtils.aes256GcmDecrypt(
                    str(resource, "ciphertext"), cfg.getApiV3Key(),
                    str(resource, "nonce"), str(resource, "associated_data"));
            Map<String, Object> order = fromJson(decrypted);

            String transactionId = str(order, "transaction_id");
            String orderId = str(order, "out_trade_no");
            Map<String, Object> amount = asMap(order.get("amount"));
            long total = toLong(amount.get("total"));
            String currency = defaultCurrency(str(amount, "currency"));
            String status = mapTradeState(str(order, "trade_state"));
            long tenantId = parseLong(str(order, "attach"));

            // 幂等：event id 重放去重；仅 SUCCESS 事件落资金事实，其余事件验签通过后仅回 ack
            if (notBlank(eventId) && !recorder.existsByEventId("wechat", eventId) && V3_SUCCESS_EVENT.equals(eventType)) {
                recorder.record("wechat", transactionId, eventId, tenantId, orderId, total, currency, status, request.rawBody());
            }
            return new VerifyCallbackResult(true, transactionId, orderId, total, currency, status, request.rawBody());
        } catch (Exception e) {
            // 验签/解密/落库任何一步失败都要留痕（不打印密钥与明文报文）。
            log.warn("微信支付 V3 回调处理失败（验签/解密/落库）", e);
            return fail(request);
        }
    }

    private QueryOrderResult queryV3(QueryOrderRequest request) {
        try {
            PayChannelProviderProperties.Wechat cfg = properties.getWechat();
            String path;
            if (notBlank(request.transactionId())) {
                path = "/v3/pay/transactions/id/" + urlEncode(request.transactionId());
            } else {
                path = "/v3/pay/transactions/out-trade-no/" + urlEncode(request.orderId());
            }
            String urlPath = path + "?mchid=" + urlEncode(cfg.getMerchantId());

            String response = restClient.get()
                    .uri(V3_BASE + urlPath)
                    .header(HttpHeaders.AUTHORIZATION, v3Authorization("GET", urlPath, ""))
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> json = fromJson(response);
            Map<String, Object> amount = asMap(json.get("amount"));
            return new QueryOrderResult("wechat",
                    str(json, "transaction_id"),
                    str(json, "out_trade_no"),
                    mapTradeState(str(json, "trade_state")),
                    toLong(amount.get("total")),
                    defaultCurrency(str(amount, "currency")));
        } catch (Exception e) {
            return new QueryOrderResult("wechat", "", request.orderId(), "FAILED", 0L, "CNY");
        }
    }

    /** 微信 v3 请求签名：method\nurl_path_with_query\ntimestamp\nnonce\nbody\n，SHA256withRSA 后 Base64。 */
    private String v3Authorization(String method, String urlPath, String body) {
        PayChannelProviderProperties.Wechat cfg = properties.getWechat();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = randomNonce();
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = PaymentCryptoUtils.rsa2Sign(message, cfg.getMerchantPrivateKey());
        return V3_AUTH_SCHEME + " mchid=\"" + cfg.getMerchantId()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + cfg.getMerchantSerialNo() + "\"";
    }

    // ---------------- shared helpers ----------------

    private static VerifyCallbackResult fail(VerifyCallbackRequest request) {
        return new VerifyCallbackResult(false, null, null, 0L, null, "FAILED", request.rawBody());
    }

    private static String buildXml(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("<xml>");
        params.forEach((k, v) -> {
            if (v != null) {
                sb.append('<').append(k).append('>').append(escapeXml(v)).append("</").append(k).append('>');
            }
        });
        sb.append("</xml>");
        return sb.toString();
    }

    private static Map<String, String> xmlToMap(String xml) {
        Map<String, String> map = new LinkedHashMap<>();
        if (xml == null || xml.isBlank()) {
            return map;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    map.put(node.getNodeName(), node.getTextContent());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析微信支付 XML 失败", e);
        }
        return map;
    }

    private static String mapTradeState(String tradeState) {
        return switch (tradeState) {
            case "SUCCESS" -> "SUCCEEDED";
            case "NOTPAY", "USERPAYING" -> "PROCESSING";
            case "REFUND", "REVOKED" -> "REFUNDED";
            case "CLOSED", "PAYERROR" -> "CLOSED";
            default -> "PROCESSING";
        };
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "");
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("微信支付 JSON 序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("微信支付 JSON 解析失败", e);
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

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
