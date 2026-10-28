package io.openware.platform.order.hotel.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.tenant.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 入站鉴权过滤器：签名/时间戳/channel 任一不合法都必须 401 且不进入控制器链。 */
class HotelChannelAuthenticationFilterTest {

    private static final String SECRET = "test-only-ota-channel-secret-0123456789abcdef";
    private static final String PATH = "/api/v1/open/hotel/v1/bookings";
    private static final String BODY = "{\"externalRef\":\"E-1\"}";

    private HotelChannelAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        HotelChannelProperties properties = new HotelChannelProperties();
        HotelChannelProperties.Channel channel = new HotelChannelProperties.Channel();
        channel.setTenantId(9001L);
        channel.setSecret(SECRET);
        Map<String, HotelChannelProperties.Channel> channels = new LinkedHashMap<>();
        channels.put("sandbox", channel);
        properties.setChannels(channels);
        filter = new HotelChannelAuthenticationFilter(properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void validSignaturePassesAndSetsThenClearsTenantContext() throws Exception {
        MockHttpServletRequest request = signedRequest(PATH, BODY, "sandbox", SECRET, System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        AtomicReference<Long> contextTenant = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            passed.set(true);
            contextTenant.set(TenantContextHolder.get().tenantId());
        });

        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
        assertEquals(9001L, contextTenant.get());
        assertNull(TenantContextHolder.get(), "过滤器必须在请求结束后清理自建的租户上下文");
    }

    @Test
    void rejectsWhenSignatureHeaderMissingOrWrong() throws Exception {
        MockHttpServletRequest missing = signedRequest(PATH, BODY, "sandbox", SECRET, System.currentTimeMillis());
        missing.removeHeader(HotelChannelSignature.SIGNATURE_HEADER);
        assertRejected(missing);

        MockHttpServletRequest wrongSecret = signedRequest(PATH, BODY, "sandbox", "another-secret-0123456789abcdef-1234",
                System.currentTimeMillis());
        assertRejected(wrongSecret);

        MockHttpServletRequest wrongBody = signedRequest(PATH, BODY, "sandbox", SECRET, System.currentTimeMillis());
        wrongBody.setContent("{\"externalRef\":\"E-2\"}".getBytes(StandardCharsets.UTF_8));
        assertRejected(wrongBody);
    }

    @Test
    void rejectsUnknownChannelAndStaleTimestamp() throws Exception {
        assertRejected(signedRequest(PATH, BODY, "unknown", SECRET, System.currentTimeMillis()));
        // 默认窗口 5 分钟，10 分钟前的时间戳必须拒绝。
        assertRejected(signedRequest(PATH, BODY, "sandbox", SECRET,
                System.currentTimeMillis() - 600_000L));
        assertRejected(signedRequest(PATH, BODY, "sandbox", SECRET, 0L));
    }

    @Test
    void doesNotTouchNonChannelPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/hotel/bookings");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> passed.set(true));

        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> passed.set(true));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("CHANNEL_UNAUTHORIZED"));
        assertEquals(false, passed.get(), "签名失败时不能继续调用控制器链");
        assertNull(TenantContextHolder.get());
    }

    private static MockHttpServletRequest signedRequest(String path, String body, String channelCode,
                                                        String secret, long timestamp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        String signature = HotelChannelSignature.sign(secret, channelCode, "POST", path, null, timestamp,
                body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(HotelChannelSignature.CHANNEL_CODE_HEADER, channelCode);
        request.addHeader(HotelChannelSignature.TIMESTAMP_HEADER, String.valueOf(timestamp));
        request.addHeader(HotelChannelSignature.SIGNATURE_HEADER, signature);
        return request;
    }
}
