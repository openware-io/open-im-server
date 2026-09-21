package com.gvchat.common.sms.infra.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.gvchat.common.sms.domain.PhonePrivacy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 腾讯云短信（sms SendSms，TC3-HMAC-SHA256 签名）。
 *
 * <p>配置占位（{@code sms.tencent.*} 默认为空）时 {@link #enabled()} 返回 false，
 * {@link #send} 仅记录日志并返回 mock 请求 ID、不抛异常；配置 secretId/secretKey/sdkAppId/signName 后启用真实发送。</p>
 */
@Component
@Slf4j
public class TencentSmsProvider implements SmsProvider {

    private static final String SERVICE = "sms";
    private static final String VERSION = "2021-01-11";
    private static final String ACTION = "SendSms";
    private static final String LF = "\n";

    @Value("${sms.tencent.secret-id:}")
    private String secretId;
    @Value("${sms.tencent.secret-key:}")
    private String secretKey;
    @Value("${sms.tencent.sdk-app-id:}")
    private String sdkAppId;
    @Value("${sms.tencent.sign-name:}")
    private String defaultSignName;
    @Value("${sms.tencent.endpoint:sms.tencentcloudapi.com}")
    private String endpoint;
    @Value("${sms.tencent.region:ap-guangzhou}")
    private String region;

    @Override
    public String provider() {
        return "tencent";
    }

    @Override
    public boolean enabled() {
        return isSet(secretId) && isSet(secretKey) && isSet(sdkAppId) && isSet(defaultSignName);
    }

    @Override
    public String send(String signName, String templateCode, String phone, Map<String, String> params) {
        if (!enabled()) {
            log.info("[sms] tencent not configured (placeholder), would send to={} template={}",
                PhonePrivacy.mask(phone), templateCode);
            return "mock-" + UUID.randomUUID();
        }
        try {
            String sign = isSet(signName) ? signName : defaultSignName;
            List<String> values = params == null ? List.of() : new ArrayList<>(params.values());
            String body = buildBody(phone, sdkAppId, sign, templateCode, values);

            long timestamp = Instant.now().getEpochSecond();
            String date = Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String host = endpoint;
            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            String canonicalHeaders = "content-type:application/json; charset=utf-8" + LF + "host:" + host + LF;
            String signedHeaders = "content-type;host";
            String hashedPayload = sha256Hex(body);
            String canonicalRequest = String.join(LF, "POST", "/", "", canonicalHeaders, signedHeaders, hashedPayload);
            String stringToSign = String.join(LF, "TC3-HMAC-SHA256", String.valueOf(timestamp), credentialScope, sha256Hex(canonicalRequest));
            byte[] secretDate = hmac("TC3" + secretKey, date);
            byte[] secretService = hmac(secretDate, SERVICE);
            byte[] secretSigning = hmac(secretService, "tc3_request");
            String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
            String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/"))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Host", host)
                .header("X-TC-Action", ACTION)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("X-TC-Region", region)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[sms] tencent sent to={} template={} httpStatus={}", PhonePrivacy.mask(phone), templateCode, response.statusCode());
            return response.body();
        } catch (Exception ex) {
            log.error("[sms] tencent failed to send to={}", PhonePrivacy.mask(phone), ex);
            throw new IllegalStateException("Failed to send tencent sms", ex);
        }
    }

    private static String buildBody(String phone, String sdkAppId, String sign, String templateId, List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('{').append('"').append("PhoneNumberSet").append('"').append(':').append('[');
        sb.append('"').append("+86").append(phone).append('"').append(']').append(',');
        sb.append('"').append("SmsSdkAppId").append('"').append(':').append('"').append(sdkAppId).append('"').append(',');
        sb.append('"').append("SignName").append('"').append(':').append('"').append(sign).append('"').append(',');
        sb.append('"').append("TemplateId").append('"').append(':').append('"').append(templateId).append('"').append(',');
        sb.append('"').append("TemplateParamSet").append('"').append(':').append(toJsonArray(values));
        return sb.append('}').toString();
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) { sb.append(','); }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static String sha256Hex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
