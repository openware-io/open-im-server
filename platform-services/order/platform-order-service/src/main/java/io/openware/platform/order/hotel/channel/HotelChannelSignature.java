package io.openware.platform.order.hotel.channel;

import io.openware.infrastructure.security.InternalServiceSignature;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 酒店 OTA 渠道 HMAC-SHA256 签名（v1）。
 *
 * <p>canonical request 固定为（字段顺序与分隔符不可变更）：
 *
 * <pre>
 * channelCode \n
 * HTTP-METHOD \n
 * requestPath \n          // 服务端 request.getRequestURI()，经网关不剥前缀，与外部路径一致
 * queryString \n          // 无 query 时为空串
 * timestamp \n            // epoch 毫秒，X-Channel-Timestamp
 * sha256Hex(body)         // 空体为 sha256("")
 * </pre>
 *
 * 签名 = hex(HMAC-SHA256(secret, canonical))，即 {@code X-Channel-Signature}。
 * 请求体哈希与内部服务签名共用 {@link InternalServiceSignature#contentHash(byte[])}，避免两套口径。
 */
public final class HotelChannelSignature {

    public static final String CHANNEL_CODE_HEADER = "X-Channel-Code";
    public static final String TIMESTAMP_HEADER = "X-Channel-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Channel-Signature";

    private HotelChannelSignature() {
    }

    /** 请求体 SHA-256 十六进制小写；空体按空字节数组计算。 */
    public static String contentHash(byte[] body) {
        return InternalServiceSignature.contentHash(body);
    }

    /** 生成 canonical request 字符串。 */
    public static String canonical(String channelCode, String method, String path, String query,
                                   long timestamp, byte[] body) {
        return channelCode + "\n" + method + "\n" + path + "\n"
                + (query == null ? "" : query) + "\n" + timestamp + "\n" + contentHash(body);
    }

    /** 生成 HMAC-SHA256 签名（十六进制小写）。 */
    public static String sign(String secret, String channelCode, String method, String path, String query,
                              long timestamp, byte[] body) {
        return hmacHex(secret, canonical(channelCode, method, path, query, timestamp, body));
    }

    /** 常量时间比较，避免按字节比较泄露签名前缀。 */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    /** 签名必须是 64 位十六进制小写。 */
    public static boolean isSignatureFormatValid(String signature) {
        return signature != null && signature.matches("[0-9a-f]{64}");
    }

    private static String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to create hotel channel signature", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsignedValue = Byte.toUnsignedInt(value);
            builder.append(hex[unsignedValue >>> 4]);
            builder.append(hex[unsignedValue & 0x0f]);
        }
        return builder.toString();
    }
}
