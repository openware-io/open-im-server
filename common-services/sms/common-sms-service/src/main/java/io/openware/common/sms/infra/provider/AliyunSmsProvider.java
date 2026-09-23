package io.openware.common.sms.infra.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.openware.common.sms.domain.PhonePrivacy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信（dysmsapi SendSms，RPC 签名）。
 *
 * <p>配置占位（{@code sms.aliyun.*} 默认为空）时 {@link #enabled()} 返回 false，
 * {@link #send} 仅记录日志并返回 mock 请求 ID、不抛异常；配置 accessKeyId/accessKeySecret/signName 后启用真实发送。</p>
 */
@Component
@Slf4j
public class AliyunSmsProvider implements SmsProvider {

    @Value("${sms.aliyun.access-key-id:}")
    private String accessKeyId;
    @Value("${sms.aliyun.access-key-secret:}")
    private String accessKeySecret;
    @Value("${sms.aliyun.sign-name:}")
    private String defaultSignName;
    @Value("${sms.aliyun.endpoint:dysmsapi.aliyuncs.com}")
    private String endpoint;

    @Override
    public String provider() {
        return "aliyun";
    }

    @Override
    public boolean enabled() {
        return isSet(accessKeyId) && isSet(accessKeySecret) && isSet(defaultSignName);
    }

    @Override
    public String send(String signName, String templateCode, String phone, Map<String, String> params) {
        if (!enabled()) {
            log.info("[sms] aliyun not configured (placeholder), would send to={} template={}",
                PhonePrivacy.mask(phone), templateCode);
            return "mock-" + UUID.randomUUID();
        }
        try {
            String sign = isSet(signName) ? signName : defaultSignName;
            Map<String, String> query = new TreeMap<>();
            query.put("AccessKeyId", accessKeyId);
            query.put("Action", "SendSms");
            query.put("Format", "JSON");
            query.put("PhoneNumbers", phone);
            query.put("RegionId", "cn-hangzhou");
            query.put("SignName", sign);
            query.put("SignatureMethod", "HMAC-SHA1");
            query.put("SignatureNonce", UUID.randomUUID().toString());
            query.put("SignatureVersion", "1.0");
            query.put("TemplateCode", templateCode);
            query.put("TemplateParam", toJson(params));
            query.put("Timestamp", Instant.now().atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
            query.put("Version", "2017-05-25");

            String canonical = toQueryString(query);
            String stringToSign = "POST&%2F&" + percentEncode(canonical);
            query.put("Signature", hmacSha1Base64(accessKeySecret + "&", stringToSign));
            String body = toQueryString(query);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + endpoint + "/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[sms] aliyun sent to={} template={} httpStatus={}", PhonePrivacy.mask(phone), templateCode, response.statusCode());
            return response.body();
        } catch (Exception ex) {
            log.error("[sms] aliyun failed to send to={}", PhonePrivacy.mask(phone), ex);
            throw new IllegalStateException("Failed to send aliyun sms", ex);
        }
    }

    private static String toJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) { sb.append(","); }
            first = false;
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String toQueryString(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) { sb.append("&"); }
            sb.append(percentEncode(e.getKey())).append("=").append(percentEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacSha1Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
