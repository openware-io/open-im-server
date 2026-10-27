package io.openware.platform.order.hotel.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 渠道 HMAC canonical request 与签名算法单元测试（不依赖 Spring）。 */
class HotelChannelSignatureTest {

    private static final String SECRET = "test-only-ota-channel-secret-0123456789abcdef";

    @Test
    void canonicalRequestHasFixedFieldOrder() {
        String canonical = HotelChannelSignature.canonical("sandbox", "POST",
                "/api/v1/open/hotel/v1/bookings", null, 1700000000000L, "{}".getBytes(StandardCharsets.UTF_8));

        String[] lines = canonical.split("\n", -1);
        assertEquals("sandbox", lines[0]);
        assertEquals("POST", lines[1]);
        assertEquals("/api/v1/open/hotel/v1/bookings", lines[2]);
        assertEquals("", lines[3]);
        assertEquals("1700000000000", lines[4]);
        assertEquals(64, lines[5].length());
    }

    @Test
    void signatureIsStableAndSensitiveToBodyPathAndChannel() {
        String path = "/api/v1/open/hotel/v1/bookings";
        byte[] body = "{\"externalRef\":\"E1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HotelChannelSignature.sign(SECRET, "sandbox", "POST", path, null, 1700000000000L, body);

        assertTrue(HotelChannelSignature.isSignatureFormatValid(signature));
        assertTrue(HotelChannelSignature.matches(signature,
                HotelChannelSignature.sign(SECRET, "sandbox", "POST", path, null, 1700000000000L, body)));
        assertFalse(HotelChannelSignature.matches(signature,
                HotelChannelSignature.sign(SECRET, "sandbox", "POST", path, null, 1700000000000L,
                        "{}".getBytes(StandardCharsets.UTF_8))));
        assertFalse(HotelChannelSignature.matches(signature,
                HotelChannelSignature.sign(SECRET, "sandbox", "POST", path + "/x", null, 1700000000000L, body)));
        assertFalse(HotelChannelSignature.matches(signature,
                HotelChannelSignature.sign(SECRET, "other", "POST", path, null, 1700000000000L, body)));
    }

    @Test
    void matchesPinnedKnownAnswerVector() {
        // 与 scripts/verify/ota-channel-client.ps1 同一测试向量；两侧算法漂移会直接失败。
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                HotelChannelSignature.contentHash(body));
        assertEquals("36a350e90ca82500b5079bf9c6826dae09b346c4ee4c5393af1043b33faeb143",
                HotelChannelSignature.sign(SECRET, "sandbox", "POST",
                        "/api/v1/open/hotel/v1/bookings", null, 1700000000000L, body));
    }

    @Test
    void rejectsMalformedSignatureAtFormatLevel() {
        assertFalse(HotelChannelSignature.isSignatureFormatValid(null));
        assertFalse(HotelChannelSignature.isSignatureFormatValid("abc"));
        assertFalse(HotelChannelSignature.isSignatureFormatValid("A".repeat(64)));
        assertFalse(HotelChannelSignature.matches("a".repeat(64), "b".repeat(64)));
    }
}
