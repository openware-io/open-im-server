package io.openware.platform.order.hotel.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import io.openware.platform.order.hotel.HotelBookingApplicationService;
import io.openware.platform.order.hotel.HotelBookingPo;
import io.openware.platform.order.hotel.HotelMapper;
import io.openware.platform.order.hotel.HotelPo;
import io.openware.platform.order.hotel.HotelStoreScopeResolver;
import io.openware.platform.order.hotel.channel.HotelChannelApi.BookingCreateRequest;
import io.openware.platform.order.hotel.channel.HotelChannelApi.BookingResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 酒店 OTA channel v1 预订适配器。
 *
 * <p>关键约束：
 * <ul>
 *   <li>创建/查询/取消都复用 {@link HotelBookingApplicationService}：房量占用/确认/取消、订单应收、
 *       取消政策全部由既有酒店域逻辑执行，适配器不直接写 {@code htl_booking}；</li>
 *   <li>organization_id / business_type / store_id 只来自 {@code htl_hotel.store_id} + {@code tnt_store}
 *       权威数据（{@link HotelStoreScopeResolver}），外部请求没有任何字段能覆盖；</li>
 *   <li>(channel_code, external_ref) 是幂等键：先以唯一键占位再创建预订，重复/并发回放都返回同一张单；</li>
 *   <li>本表 status 只是投影，响应里的状态读 {@code htl_booking} 实时状态。</li>
 * </ul>
 */
@Service
@Slf4j
public class HotelChannelBookingApplicationService {

    private static final int MAX_EXTERNAL_REF_LENGTH = 128;
    private static final int MAX_ROOMS = 20;

    private final HotelChannelBookingMapper channelBookingMapper;
    private final HotelMapper hotelMapper;
    private final HotelBookingApplicationService bookingService;
    private final HotelStoreScopeResolver storeScopeResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HotelChannelBookingApplicationService(HotelChannelBookingMapper channelBookingMapper,
                                                HotelMapper hotelMapper,
                                                HotelBookingApplicationService bookingService,
                                                HotelStoreScopeResolver storeScopeResolver) {
        this.channelBookingMapper = channelBookingMapper;
        this.hotelMapper = hotelMapper;
        this.bookingService = bookingService;
        this.storeScopeResolver = storeScopeResolver;
    }

    /** 创建结果：{@code replay=true} 表示外部单号已存在、按幂等回放返回 200。 */
    public record CreateResult(BookingResponse response, boolean replay) {
    }

    /** 并发下 (channel_code, external_ref) 唯一键冲突：由控制器转成一次回放查询。 */
    public static class ChannelBookingConflictException extends RuntimeException {
        public ChannelBookingConflictException(Throwable cause) {
            super("channel booking external ref conflict", cause);
        }
    }

    /**
     * 创建 OTA 预订：同 externalRef 同内容回放 200，同 externalRef 不同内容 409。
     *
     * <p>先插入映射占位（唯一键兜底并发），再调酒店域创建预订；任何一步失败整体回滚，
     * 不会留下「渠道单已占位、房量未扣」或「房量已扣、渠道映射缺失」的半成品。
     */
    @Transactional
    public CreateResult create(String channelCode, BookingCreateRequest request) {
        String code = normalizeChannelCode(channelCode);
        validateCreate(request);
        HotelPo hotel = resolveHotel(request.hotelRef());
        applyHotelScope(hotel.getStoreId());
        String requestHash = requestHash(code, request);

        HotelChannelBookingPo existing = findExisting(code, request.externalRef());
        if (existing != null) {
            return new CreateResult(replay(existing, requestHash), true);
        }

        HotelChannelBookingPo row = newRow(code, request.externalRef(), requestHash);
        row.setStatus("PENDING");
        row.setLastAction("CREATE_REQUESTED");
        row.setAuditJson(json(List.of(auditEntry("CREATE_REQUESTED", null))));
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            channelBookingMapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            // 唯一键在并发下挡住第二个请求：让本次事务整体回滚，控制器再按 externalRef 回放。
            throw new ChannelBookingConflictException(duplicate);
        }

        HotelBookingPo booking = bookingService.createBooking(
                Long.parseLong(request.roomTypeRef().trim()),
                request.ratePlanRef() == null || request.ratePlanRef().isBlank()
                        ? null : Long.parseLong(request.ratePlanRef().trim()),
                request.checkIn(), request.checkOut(), request.rooms(),
                request.guest().name(), request.guest().phone(), null,
                null, 0L, channelSource(code));
        verifyServerAmount(request, booking);

        row.setBookingId(booking.getId());
        row.setBookingNo(booking.getBookingNo());
        row.setStatus(booking.getStatus());
        row.setLastAction("CREATED");
        row.setAuditJson(appendAudit(row.getAuditJson(), auditEntry("CREATED", booking)));
        row.setUpdatedAt(LocalDateTime.now());
        channelBookingMapper.updateById(row);
        return new CreateResult(view(row, booking), false);
    }

    /**
     * 并发唯一键冲突后的回放：重新按 externalRef 读映射并返回既有预订（200）。
     * 若同单号内容不同，仍是 409，不覆盖已存在预订。
     */
    public BookingResponse replayAfterConflict(String channelCode, BookingCreateRequest request) {
        String code = normalizeChannelCode(channelCode);
        validateCreate(request);
        HotelChannelBookingPo existing = findExisting(code, request.externalRef());
        if (existing == null) {
            // 竞争者事务回滚时本请求的占位插入会成功，不会走到这里；出现即按可重试冲突处理。
            throw new ApiException(409, "CHANNEL_BOOKING_CONFLICT_RETRY", "同一预订并发创建冲突，请重试");
        }
        return replay(existing, requestHash(code, request));
    }

    /** 按外部单号查询；不存在 404，状态以 htl_booking 实时状态为准。 */
    public BookingResponse get(String channelCode, String externalRef) {
        String code = normalizeChannelCode(channelCode);
        HotelChannelBookingPo mapping = requireMapping(code, externalRef);
        return view(mapping, loadBooking(mapping));
    }

    /**
     * 取消：结果幂等——已取消（含前台先取消）返回 200 当前状态；状态机不允许取消（如已退房）409。
     */
    public BookingResponse cancel(String channelCode, String externalRef, String reason) {
        String code = normalizeChannelCode(channelCode);
        HotelChannelBookingPo mapping = requireMapping(code, externalRef);
        HotelBookingPo booking = loadBooking(mapping);
        if ("CANCELLED".equals(booking.getStatus())) {
            syncStatus(mapping, booking, "CANCEL_REPLAY", reason);
            return view(mapping, booking);
        }
        try {
            HotelBookingPo cancelled = bookingService.cancel(booking.getId());
            syncStatus(mapping, cancelled, "CANCELLED", reason);
            return view(mapping, cancelled);
        } catch (ApiException conflict) {
            // 并发取消/前台已取消：booking.cancel 的事务已回滚，这里读实时状态再判定幂等结果。
            HotelBookingPo latest = bookingService.getBooking(mapping.getBookingId());
            if ("CANCELLED".equals(latest.getStatus())) {
                syncStatus(mapping, latest, "CANCEL_REPLAY", reason);
                return view(mapping, latest);
            }
            throw conflict;
        }
    }

    private BookingResponse replay(HotelChannelBookingPo mapping, String requestHash) {
        if (!Objects.equals(mapping.getRequestHash(), requestHash)) {
            throw new ApiException(409, "CHANNEL_BOOKING_REQUEST_MISMATCH",
                    "同一 externalRef 已存在且请求内容不一致，不能覆盖既有预订");
        }
        return view(mapping, loadBooking(mapping));
    }

    private HotelChannelBookingPo newRow(String channelCode, String externalRef, String requestHash) {
        HotelChannelBookingPo row = new HotelChannelBookingPo();
        TenantContext context = requireContext();
        row.setTenantId(context.tenantId());
        row.setStoreId(context.storeId());
        row.setChannelCode(channelCode);
        row.setExternalRef(externalRef.trim());
        row.setRequestHash(requestHash);
        return row;
    }

    private HotelChannelBookingPo requireMapping(String channelCode, String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            throw new ApiException(400, "CHANNEL_EXTERNAL_REF_INVALID", "externalRef 不能为空");
        }
        HotelChannelBookingPo mapping = findExisting(channelCode, externalRef.trim());
        if (mapping == null) {
            throw new ApiException(404, "CHANNEL_BOOKING_NOT_FOUND", "渠道预订不存在");
        }
        return mapping;
    }

    private HotelChannelBookingPo findExisting(String channelCode, String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            return null;
        }
        return channelBookingMapper.selectOne(new LambdaQueryWrapper<HotelChannelBookingPo>()
                .eq(HotelChannelBookingPo::getChannelCode, channelCode)
                .eq(HotelChannelBookingPo::getExternalRef, externalRef.trim()));
    }

    /** 读映射指向的 htl_booking：先按映射 store 复核门店权威业态，再走酒店域查询。 */
    private HotelBookingPo loadBooking(HotelChannelBookingPo mapping) {
        applyHotelScope(mapping.getStoreId());
        HotelBookingPo booking = bookingService.getBooking(mapping.getBookingId());
        if (booking == null) {
            throw new ApiException(500, "CHANNEL_BOOKING_ORPHANED", "渠道预订映射指向的酒店预订不存在");
        }
        if (!Objects.equals(booking.getStoreId(), mapping.getStoreId())) {
            throw new ApiException(409, "CHANNEL_BOOKING_SCOPE_MISMATCH", "渠道预订与映射门店不一致");
        }
        return booking;
    }

    /** 解析酒店并把上下文收敛到门店权威作用域（organization/business 只来自 tnt_store）。 */
    private HotelPo resolveHotel(String hotelRef) {
        long hotelId;
        try {
            hotelId = Long.parseLong(hotelRef.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "HOTEL_REF_INVALID", "hotelRef 必须是酒店 ID");
        }
        TenantContext context = requireContext();
        HotelPo hotel = hotelMapper.selectById(hotelId);
        if (hotel == null || !Objects.equals(hotel.getTenantId(), context.tenantId())
                || !"ACTIVE".equalsIgnoreCase(hotel.getStatus()) || hotel.getStoreId() == null) {
            throw new ApiException(404, "HOTEL_NOT_FOUND", "酒店不存在或未启用");
        }
        return hotel;
    }

    private StoreScopeRepository.StoreScope applyHotelScope(Long storeId) {
        TenantContext current = requireContext();
        TenantContext provisional = HotelChannelContexts.provisional(current.tenantId());
        TenantContextHolder.set(new TenantContext(provisional.tenantId(), null, storeId, 0L, 0,
                HotelChannelContexts.ADAPTER_PERMISSIONS, null, null, null));
        StoreScopeRepository.StoreScope scope;
        try {
            scope = storeScopeResolver.requireHotelScope(TenantContextHolder.get());
        } catch (RuntimeException e) {
            TenantContextHolder.set(current);
            throw e;
        }
        TenantContextHolder.set(HotelChannelContexts.scoped(scope.tenantId(), scope.organizationId(),
                scope.storeId(), scope.businessType()));
        return scope;
    }

    private void verifyServerAmount(BookingCreateRequest request, HotelBookingPo booking) {
        if (!Objects.equals(booking.getAmountMinor(), request.amountMinor())) {
            throw new ApiException(422, "CHANNEL_AMOUNT_MISMATCH",
                    "金额与服务端报价不一致，服务端金额为 " + booking.getAmountMinor());
        }
        if (booking.getCurrency() != null && request.currency() != null
                && !booking.getCurrency().equalsIgnoreCase(request.currency().trim())) {
            throw new ApiException(422, "CHANNEL_CURRENCY_MISMATCH",
                    "币种与服务端报价不一致，服务端币种为 " + booking.getCurrency());
        }
    }

    private void syncStatus(HotelChannelBookingPo mapping, HotelBookingPo booking, String action, String reason) {
        mapping.setStatus(booking.getStatus());
        mapping.setLastAction(action);
        if (reason != null && !reason.isBlank()) {
            mapping.setCancelReason(reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim());
        }
        mapping.setAuditJson(appendAudit(mapping.getAuditJson(), auditEntry(action, booking)));
        mapping.setUpdatedAt(LocalDateTime.now());
        channelBookingMapper.updateById(mapping);
    }

    private BookingResponse view(HotelChannelBookingPo mapping, HotelBookingPo booking) {
        return new BookingResponse(
                booking.getBookingNo(),
                mapping.getExternalRef(),
                booking.getStatus(),
                booking.getHotelId() == null ? null : String.valueOf(booking.getHotelId()),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                booking.getRooms(),
                booking.getCurrency(),
                booking.getAmountMinor(),
                booking.getRefundMinor(),
                booking.getCancelFeeMinor(),
                booking.getCancelledAt(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }

    private Map<String, Object> auditEntry(String action, HotelBookingPo booking) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", action);
        entry.put("at", LocalDateTime.now().toString());
        if (booking != null) {
            entry.put("bookingNo", booking.getBookingNo());
            entry.put("status", booking.getStatus());
        }
        return entry;
    }

    private String appendAudit(String existing, Map<String, Object> entry) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            try {
                entries = objectMapper.readValue(existing, new TypeReference<>() { });
            } catch (Exception e) {
                log.warn("hotel channel booking audit json is unreadable, restarting the trail", e);
                entries = new ArrayList<>();
            }
        }
        entries.add(entry);
        return json(entries);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize hotel channel booking audit", e);
        }
    }

    /** 幂等指纹：只覆盖会影响酒店预订结果的字段，与 JSON 字段顺序/空白无关。 */
    private static String requestHash(String channelCode, BookingCreateRequest request) {
        String canonical = channelCode + "|"
                + request.externalRef().trim() + "|"
                + request.hotelRef().trim() + "|"
                + request.roomTypeRef().trim() + "|"
                + nullToEmpty(request.ratePlanRef()) + "|"
                + request.checkIn() + "|"
                + request.checkOut() + "|"
                + request.rooms() + "|"
                + (request.guests() == null ? "" : request.guests()) + "|"
                + request.guest().name().trim() + "|"
                + request.guest().phone().trim() + "|"
                + request.amountMinor() + "|"
                + request.currency().trim().toUpperCase(Locale.ROOT) + "|"
                + nullToEmpty(request.paymentResponsibility()).toUpperCase(Locale.ROOT);
        return HotelChannelSignature.contentHash(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeChannelCode(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            throw new ApiException(401, "CHANNEL_UNAUTHORIZED", "缺少渠道编码");
        }
        return channelCode.trim();
    }

    private static String channelSource(String channelCode) {
        return "OTA_" + channelCode.trim().toUpperCase(Locale.ROOT);
    }

    private static TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "CHANNEL_CONTEXT_MISSING", "缺少渠道租户上下文");
        }
        return context;
    }

    private void validateCreate(BookingCreateRequest request) {
        if (request == null) {
            throw new ApiException(400, "CHANNEL_REQUEST_REQUIRED", "请求体不能为空");
        }
        if (request.externalRef() == null || request.externalRef().isBlank()
                || request.externalRef().trim().length() > MAX_EXTERNAL_REF_LENGTH
                || !request.externalRef().trim().matches("[A-Za-z0-9._:-]+")) {
            throw new ApiException(400, "CHANNEL_EXTERNAL_REF_INVALID",
                    "externalRef 必填、最长 128 且只能包含字母/数字/._:-");
        }
        requireNumeric(request.hotelRef(), "HOTEL_REF_INVALID", "hotelRef");
        requireNumeric(request.roomTypeRef(), "ROOM_TYPE_REF_INVALID", "roomTypeRef");
        if (request.ratePlanRef() != null && !request.ratePlanRef().isBlank()) {
            requireNumeric(request.ratePlanRef(), "RATE_PLAN_REF_INVALID", "ratePlanRef");
        }
        if (request.checkIn() == null || request.checkOut() == null
                || !request.checkOut().isAfter(request.checkIn())) {
            throw new ApiException(400, "STAY_INVALID", "入住/离店日期不合法");
        }
        if (request.rooms() == null || request.rooms() < 1 || request.rooms() > MAX_ROOMS) {
            throw new ApiException(400, "ROOMS_INVALID", "rooms 必须在 1 到 20 之间");
        }
        if (request.guests() != null && request.guests() < 1) {
            throw new ApiException(400, "GUESTS_INVALID", "guests 必须大于 0");
        }
        if (request.guest() == null || request.guest().name() == null || request.guest().name().isBlank()
                || request.guest().phone() == null || request.guest().phone().isBlank()) {
            throw new ApiException(400, "GUEST_REQUIRED", "住客姓名和手机号不能为空");
        }
        if (request.amountMinor() == null || request.amountMinor() < 0) {
            throw new ApiException(400, "AMOUNT_INVALID", "amountMinor 必须是非负整数");
        }
        if (request.currency() == null || request.currency().trim().length() != 3) {
            throw new ApiException(400, "CURRENCY_INVALID", "currency 必须是 3 位币种代码");
        }
    }

    private static void requireNumeric(String value, String code, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, code, field + " 不能为空");
        }
        try {
            Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, code, field + " 必须是数字 ID");
        }
    }
}
