package io.openware.platform.order.hotel.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.mq.EventOutboxRelay;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 酒店 OTA channel v1 HTTP 集成测试：真跑 H2 + Flyway + MyBatis + 真实验签过滤器 + 既有
 * {@link io.openware.platform.order.hotel.HotelBookingApplicationService}，只 mock MQ/审计基础设施。
 *
 * <p>覆盖：401 验签失败、201 创建、200 externalRef 回放、409 请求内容不一致、409 房量不足、
 * 查询 200/404、取消幂等并释放房量。断言同时查 {@code htl_booking} 与 {@code htl_channel_booking}，
 * 防止适配器绕过酒店域直接写预订表。
 */
@SpringBootTest
@ActiveProfiles("test")
class HotelChannelBookingIntegrationTest {

    private static final long TENANT_ID = 9001L;
    private static final long STORE_ID = 99201L;
    private static final long ORGANIZATION_ID = 99201L;
    private static final long HOTEL_ID = 99001L;
    private static final long ROOM_TYPE_ID = 99001L;
    private static final long RATE_PLAN_ID = 99001L;
    private static final String CHANNEL = "sandbox";
    private static final String SECRET = "test-only-ota-channel-secret-0123456789abcdef";
    private static final long NIGHTLY_PRICE = 50_000L;
    private static final String BOOKINGS_PATH = "/api/v1/open/hotel/v1/bookings";

    @MockitoBean
    private MqProducer mqProducer;

    @MockitoBean
    private MqConsumerFactory mqConsumerFactory;

    @MockitoBean
    private AuditClient auditClient;

    @MockitoBean
    private EventOutboxRelay eventOutboxRelay;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private HotelChannelAuthenticationFilter authenticationFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(authenticationFilter)
                .build();
        checkIn = LocalDate.now().plusDays(7);
        checkOut = checkIn.plusDays(2);
        resetTenantData();
        seedHotelDomain();
        seedAvailability(5);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void unsignedRequestIsRejectedWithoutWritingAnything() throws Exception {
        mockMvc.perform(post(BOOKINGS_PATH).contentType("application/json")
                        .content(createBody("E-401", 1, amountFor(1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CHANNEL_UNAUTHORIZED"));

        assertEquals(0, count("SELECT COUNT(*) FROM htl_channel_booking WHERE tenant_id = ?", TENANT_ID));
        assertEquals(0, count("SELECT COUNT(*) FROM htl_booking WHERE tenant_id = ?", TENANT_ID));
    }

    @Test
    void validSignatureCreatesBookingAndWritesBothTables() throws Exception {
        String body = createBodyWithHostileTenantFields("E-201", 2, amountFor(2));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, body)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRef").value("E-201"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.rooms").value(2))
                .andExpect(jsonPath("$.amountMinor").value(amountFor(2)))
                .andExpect(jsonPath("$.hotelRef").value(String.valueOf(HOTEL_ID)));

        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT * FROM htl_booking WHERE tenant_id = ? AND status = 'PENDING_CONFIRM'", TENANT_ID);
        assertEquals(STORE_ID, ((Number) booking.get("store_id")).longValue());
        assertEquals(ORGANIZATION_ID, ((Number) booking.get("organization_id")).longValue());
        assertEquals("HOTEL", booking.get("business_type"));
        assertEquals(2, ((Number) booking.get("rooms")).longValue());
        assertEquals("OTA_SANDBOX", booking.get("source"));
        assertNotNull(booking.get("order_id"), "预订必须生成 ord_order 应收");

        Map<String, Object> mapping = jdbcTemplate.queryForMap(
                "SELECT * FROM htl_channel_booking WHERE tenant_id = ? AND external_ref = ?", TENANT_ID, "E-201");
        assertEquals(CHANNEL, mapping.get("channel_code"));
        assertEquals(((Number) booking.get("id")).longValue(), ((Number) mapping.get("booking_id")).longValue());
        assertEquals(booking.get("booking_no"), mapping.get("booking_no"));
        assertEquals(64, String.valueOf(mapping.get("request_hash")).length());
        assertTrue(String.valueOf(mapping.get("audit_json")).contains("CREATED"));

        assertEquals(2, count("SELECT COUNT(*) FROM htl_room_availability WHERE tenant_id = ? AND held = 2",
                TENANT_ID));
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT * FROM ord_order WHERE tenant_id = ? AND idempotency_key = ?",
                TENANT_ID, "hotel-booking:" + booking.get("booking_no"));
        assertEquals("DRAFT", order.get("trade_status"));
        assertEquals(amountFor(2), ((Number) order.get("total_amount")).longValue());
    }

    @Test
    void replaySameExternalRefReturns200AndDoesNotDoubleHoldRooms() throws Exception {
        String body = createBody("E-REPLAY", 2, amountFor(2));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, body)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, body)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalRef").value("E-REPLAY"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"));

        assertEquals(1, count("SELECT COUNT(*) FROM htl_channel_booking WHERE tenant_id = ?", TENANT_ID));
        assertEquals(1, count("SELECT COUNT(*) FROM htl_booking WHERE tenant_id = ?", TENANT_ID));
        assertEquals(2, count("SELECT COUNT(*) FROM htl_room_availability WHERE tenant_id = ? AND held = 2",
                TENANT_ID));
    }

    @Test
    void sameExternalRefWithDifferentPayloadIsRejectedWith409() throws Exception {
        String first = createBody("E-MISMATCH", 1, amountFor(1));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, first)
                        .contentType("application/json").content(first))
                .andExpect(status().isCreated());

        String changed = createBody("E-MISMATCH", 2, amountFor(2));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, changed)
                        .contentType("application/json").content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_BOOKING_REQUEST_MISMATCH"));

        assertEquals(1, count("SELECT COUNT(*) FROM htl_booking WHERE tenant_id = ?", TENANT_ID));
    }

    @Test
    void inventoryShortageReturns409AndLeavesNoChannelRow() throws Exception {
        String first = createBody("E-STOCK-1", 4, amountFor(4));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, first)
                        .contentType("application/json").content(first))
                .andExpect(status().isCreated());

        String second = createBody("E-STOCK-2", 2, amountFor(2));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, second)
                        .contentType("application/json").content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_AVAILABILITY_UNAVAILABLE"));

        assertEquals(1, count("SELECT COUNT(*) FROM htl_channel_booking WHERE tenant_id = ?", TENANT_ID));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM htl_channel_booking WHERE tenant_id = ? AND external_ref = 'E-STOCK-2'",
                TENANT_ID));
        assertEquals(1, count("SELECT COUNT(*) FROM htl_booking WHERE tenant_id = ?", TENANT_ID));
    }

    @Test
    void getReturnsLiveStateAndUnknownRefReturns404() throws Exception {
        String body = createBody("E-GET", 1, amountFor(1));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, body)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        String bookingPath = BOOKINGS_PATH + "/E-GET";
        mockMvc.perform(signed(get(bookingPath), "GET", bookingPath, new byte[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalRef").value("E-GET"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"));

        String missingPath = BOOKINGS_PATH + "/E-NOT-EXIST";
        mockMvc.perform(signed(get(missingPath), "GET", missingPath, new byte[0]))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_BOOKING_NOT_FOUND"));

        String unsignedPath = BOOKINGS_PATH + "/E-GET";
        mockMvc.perform(get(unsignedPath))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancelIsIdempotentAndReleasesRoomAvailability() throws Exception {
        String body = createBody("E-CANCEL", 2, amountFor(2));
        mockMvc.perform(signed(post(BOOKINGS_PATH), "POST", BOOKINGS_PATH, body)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        String cancelPath = BOOKINGS_PATH + "/E-CANCEL/cancel";
        String cancelBody = "{\"reason\":\"OTA cancel\"}";
        mockMvc.perform(signed(post(cancelPath), "POST", cancelPath, cancelBody)
                        .contentType("application/json").content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM htl_booking WHERE tenant_id = ?", String.class, TENANT_ID));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM htl_channel_booking WHERE tenant_id = ?", String.class, TENANT_ID));
        assertEquals(0, count("SELECT COUNT(*) FROM htl_room_availability WHERE tenant_id = ? AND held > 0",
                TENANT_ID));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT audit_json FROM htl_channel_booking WHERE tenant_id = ?", String.class, TENANT_ID)
                .contains("CANCELLED"));

        mockMvc.perform(signed(post(cancelPath), "POST", cancelPath, cancelBody)
                        .contentType("application/json").content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(1, count("SELECT COUNT(*) FROM htl_channel_booking WHERE tenant_id = ?", TENANT_ID));
    }

    @Test
    void ariPushIsSignedNoOpReturning202() throws Exception {
        String path = "/api/v1/open/hotel/v1/ari";
        String body = "{\"items\":[{\"hotelRef\":\"" + HOTEL_ID + "\",\"roomTypeRef\":\"" + ROOM_TYPE_ID
                + "\",\"stayDate\":\"" + checkIn + "\",\"total\":5,\"sold\":0,\"stopSell\":false,"
                + "\"priceMinor\":50000,\"currency\":\"CNY\",\"version\":1}]}";
        mockMvc.perform(signed(post(path), "POST", path, body)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results[0].accepted").value(false))
                .andExpect(jsonPath("$.results[0].code").value("ARI_NOT_IMPLEMENTED"));
    }

    // ---- helpers ----

    private String createBody(String externalRef, int rooms, long amountMinor) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("externalRef", externalRef);
        payload.put("hotelRef", String.valueOf(HOTEL_ID));
        payload.put("roomTypeRef", String.valueOf(ROOM_TYPE_ID));
        payload.put("ratePlanRef", String.valueOf(RATE_PLAN_ID));
        payload.put("checkIn", checkIn.toString());
        payload.put("checkOut", checkOut.toString());
        payload.put("rooms", rooms);
        payload.put("guests", rooms * 2);
        payload.put("guest", Map.of("name", "OTA Guest", "phone", "13800000000"));
        payload.put("amountMinor", amountMinor);
        payload.put("currency", "CNY");
        payload.put("paymentResponsibility", "OTA_COLLECT");
        return objectMapper.writeValueAsString(payload);
    }

    /** 外部请求额外塞 org/business 字段时必须被忽略，服务端只用门店权威数据。 */
    private String createBodyWithHostileTenantFields(String externalRef, int rooms, long amountMinor)
            throws Exception {
        Map<String, Object> payload = objectMapper.readValue(createBody(externalRef, rooms, amountMinor),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        payload.put("organizationId", 1);
        payload.put("businessType", "KTV");
        payload.put("storeId", 1);
        payload.put("tenantId", 1);
        return objectMapper.writeValueAsString(payload);
    }

    private MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, String method,
                                                 String path, String body) {
        return signed(builder, method, path, body.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, String method,
                                                 String path, byte[] body) {
        long timestamp = System.currentTimeMillis();
        String signature = HotelChannelSignature.sign(SECRET, CHANNEL, method, path, null, timestamp, body);
        return builder
                .header(HotelChannelSignature.CHANNEL_CODE_HEADER, CHANNEL)
                .header(HotelChannelSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(HotelChannelSignature.SIGNATURE_HEADER, signature);
    }

    private long amountFor(int rooms) {
        return NIGHTLY_PRICE * 2 * rooms;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void resetTenantData() {
        for (String table : new String[] {"htl_channel_booking", "htl_booking_night", "htl_room_availability_hold",
                "htl_booking", "ord_order", "htl_room_availability", "htl_daily_rate", "htl_rate_plan",
                "htl_room_type", "htl_hotel", "htl_policy", "tnt_store"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }
    }

    private void seedHotelDomain() {
        jdbcTemplate.update("INSERT INTO tnt_store (id, tenant_id, organization_id, business_type, status) "
                        + "VALUES (?, ?, ?, 'HOTEL', 'ACTIVE')",
                STORE_ID, TENANT_ID, ORGANIZATION_ID);
        jdbcTemplate.update("INSERT INTO htl_hotel (id, tenant_id, store_id, name, timezone, check_in_time, "
                        + "check_out_time, status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'OTA Test Hotel', 'Asia/Shanghai', '14:00', '12:00', 'ACTIVE', 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                HOTEL_ID, TENANT_ID, STORE_ID);
        jdbcTemplate.update("INSERT INTO htl_room_type (id, tenant_id, store_id, hotel_id, code, name, capacity, "
                        + "status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'DLX', 'Deluxe King', 2, 'ACTIVE', 0, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP)",
                ROOM_TYPE_ID, TENANT_ID, STORE_ID, HOTEL_ID);
        jdbcTemplate.update("INSERT INTO htl_rate_plan (id, tenant_id, store_id, hotel_id, code, name, "
                        + "base_price_minor, currency, status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BAR', 'Best Available', ?, 'CNY', 'ACTIVE', 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                RATE_PLAN_ID, TENANT_ID, STORE_ID, HOTEL_ID, NIGHTLY_PRICE);
    }

    private void seedAvailability(int total) {
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            jdbcTemplate.update("INSERT INTO htl_room_availability (tenant_id, organization_id, store_id, "
                            + "room_type_id, stay_date, total, sold, held, version, created_by, created_at, "
                            + "updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, CURRENT_TIMESTAMP, 0, "
                            + "CURRENT_TIMESTAMP)",
                    TENANT_ID, ORGANIZATION_ID, STORE_ID, ROOM_TYPE_ID, date, total);
        }
    }
}
