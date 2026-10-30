package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import org.springframework.web.bind.annotation.RestController;

/** 酒店报价、预订、确认与取消。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel")
public class HotelBookingController {

    private final HotelBookingApplicationService service;
    private final HotelOrderQueryApplicationService orderQueryService;
    private final IdentityAccountLookupClient identityLookup;
    private final MemberLookupProvider memberLookupProvider;
    private final AuditClient auditClient;

    public HotelBookingController(HotelBookingApplicationService service,
                                  HotelOrderQueryApplicationService orderQueryService,
                                  IdentityAccountLookupClient identityLookup,
                                  MemberLookupProvider memberLookupProvider,
                                  AuditClient auditClient) {
        this.service = service;
        this.orderQueryService = orderQueryService;
        this.identityLookup = identityLookup;
        this.memberLookupProvider = memberLookupProvider;
        this.auditClient = auditClient;
    }

    @PostMapping("/quotes")
    public HotelBookingApplicationService.Quote quote(@RequestBody QuoteRequest request) {
        return service.quote(request.roomTypeId(), request.ratePlanId(), request.checkInDate(),
                request.checkOutDate(), request.rooms() == null ? 1 : request.rooms());
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelBookingPo create(@RequestBody BookingRequest request) {
        Long bookedByAccountId = request.bookedByAccountId();
        boolean autoAttributed = false;
        if (bookedByAccountId == null && request.guestPhone() != null) {
            Long candidate = identityLookup.findByPhone(request.guestPhone()).orElse(null);
            TenantContext context = TenantContextHolder.get();
            // 仅当手机号对应当前租户的有效 CUSTOMER 会员时才自动归属；
            // 否则按“无账号的到店客”建单，不能把订单挂到无关账号上。
            if (candidate != null && context != null && memberLookupProvider
                    .findMemberId(context.tenantId(), candidate).isPresent()) {
                bookedByAccountId = candidate;
                autoAttributed = true;
            }
        }
        HotelBookingPo booking = service.createBooking(request.roomTypeId(), request.ratePlanId(),
                request.checkInDate(), request.checkOutDate(), request.rooms() == null ? 1 : request.rooms(),
                request.guestName(), request.guestPhone(), request.note(), bookedByAccountId,
                request.pointsUsed());
        if (autoAttributed && booking != null) {
            Long accountId = bookedByAccountId;
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(booking.getTenantId())
                    .storeId(booking.getStoreId())
                    .action("hotel.booking.customer_attribution")
                    .resourceType("hotel_booking")
                    .resourceId(String.valueOf(booking.getId()))
                    .resourceName(booking.getBookingNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .idempotencyKey("hotel.booking.customer_attribution:" + booking.getId())
                    .detailJson("{\"accountId\":" + accountId + ",\"bookingNo\":\""
                            + booking.getBookingNo() + "\"}")
                    .build());
        }
        return booking;
    }

    @GetMapping("/bookings")
    public List<HotelBookingPo> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(status, from, to, limit, offset);
    }

    /** 酒店订单读模型：订单 + 预订 + 住客 + 房型/房号 + 入离 + 状态 + 金额。 */
    @GetMapping("/orders")
    public List<HotelOrderView> orders(@RequestParam(required = false) String status) {
        return orderQueryService.orders(status);
    }

    @PostMapping("/bookings/{id}/confirm")
    public HotelBookingPo confirm(@PathVariable Long id,
                                  @RequestParam(required = false) Integer expectedVersion) {
        return service.confirm(id, expectedVersion);
    }

    @PostMapping("/bookings/{id}/cancel")
    public HotelBookingPo cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @PostMapping("/bookings/{id}/no-show")
    public HotelBookingPo noShow(@PathVariable Long id) {
        return service.noShow(id);
    }

    public record QuoteRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate, LocalDate checkOutDate,
                               Integer rooms) {}

    public record BookingRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate, LocalDate checkOutDate,
                                 Integer rooms, String guestName, String guestPhone, String note,
                                 Long bookedByAccountId, Long pointsUsed) {}
}
