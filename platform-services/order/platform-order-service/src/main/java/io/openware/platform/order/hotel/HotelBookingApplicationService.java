package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.OrderApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 酒店报价、库存 hold、预订创建/确认/取消。库存扣减全部走原子 UPDATE。 */
@Service
public class HotelBookingApplicationService {

    private static final int HOLD_MINUTES = 15;

    private final HotelBookingMapper bookingMapper;
    private final HotelRoomTypeMapper roomTypeMapper;
    private final HotelRatePlanMapper ratePlanMapper;
    private final HotelInventoryMapper inventoryMapper;
    private final HotelInventoryHoldMapper holdMapper;
    private final HotelDailyRateMapper dailyRateMapper;
    private final HotelBookingNightMapper nightMapper;
    private final OrderApplicationService orderApplicationService;
    private final MemberLookupProvider memberLookupProvider;

    public HotelBookingApplicationService(HotelBookingMapper bookingMapper, HotelRoomTypeMapper roomTypeMapper,
                                          HotelRatePlanMapper ratePlanMapper, HotelInventoryMapper inventoryMapper,
                                          HotelInventoryHoldMapper holdMapper, HotelDailyRateMapper dailyRateMapper,
                                          HotelBookingNightMapper nightMapper,
                                          OrderApplicationService orderApplicationService,
                                          MemberLookupProvider memberLookupProvider) {
        this.bookingMapper = bookingMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.ratePlanMapper = ratePlanMapper;
        this.inventoryMapper = inventoryMapper;
        this.holdMapper = holdMapper;
        this.dailyRateMapper = dailyRateMapper;
        this.nightMapper = nightMapper;
        this.orderApplicationService = orderApplicationService;
        this.memberLookupProvider = memberLookupProvider;
    }

    public Quote quote(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        validateStay(checkIn, checkOut, rooms);
        HotelRoomTypePo roomType = requireRoomType(roomTypeId, context);
        HotelRatePlanPo ratePlan = requireRatePlan(ratePlanId, roomType.getHotelId(), context);
        List<NightQuote> nights = new ArrayList<>();
        long total = 0;
        int available = Integer.MAX_VALUE;
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            HotelInventoryPo inventory = inventory(context, roomTypeId, date);
            int dayAvailable = inventory == null ? 0
                    : Math.max(0, inventory.getTotal() - inventory.getSold() - inventory.getHeld());
            available = Math.min(available, dayAvailable);
            long price = dailyPrice(context, roomTypeId, ratePlan, date);
            nights.add(new NightQuote(date, price, dayAvailable));
            total = Math.addExact(total, Math.multiplyExact(price, rooms));
        }
        return new Quote(available >= rooms, total, ratePlan.getCurrency(), nights,
                LocalDateTime.now().plusMinutes(HOLD_MINUTES));
    }

    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note) {
        return createBooking(roomTypeId, ratePlanId, checkIn, checkOut, rooms, guestName, guestPhone, note, null);
    }

    @Transactional
    public HotelBookingPo createBooking(Long roomTypeId, Long ratePlanId, LocalDate checkIn, LocalDate checkOut,
                                        int rooms, String guestName, String guestPhone, String note,
                                        Long bookedByAccountId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        if (guestName == null || guestName.isBlank() || guestPhone == null || guestPhone.isBlank()) {
            throw new ApiException(400, "GUEST_REQUIRED", "住客姓名和手机号不能为空");
        }
        Quote quote = quote(roomTypeId, ratePlanId, checkIn, checkOut, rooms);
        if (!quote.available()) {
            throw new ApiException(409, "INVENTORY_UNAVAILABLE", "所选日期房量不足");
        }
        HotelRoomTypePo roomType = requireRoomType(roomTypeId, context);
        HotelRatePlanPo ratePlan = requireRatePlan(ratePlanId, roomType.getHotelId(), context);
        LocalDateTime now = LocalDateTime.now();
        HotelBookingPo booking = new HotelBookingPo();
        booking.setTenantId(context.tenantId());
        booking.setStoreId(context.storeId());
        booking.setHotelId(roomType.getHotelId());
        booking.setRoomTypeId(roomTypeId);
        booking.setRatePlanId(ratePlanId);
        booking.setBookingNo("HTL" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(100, 999));
        booking.setGuestName(guestName.trim());
        booking.setGuestPhone(guestPhone);
        booking.setRoomType(roomType.getName());
        booking.setRooms(rooms);
        booking.setNights((int) ChronoUnit.DAYS.between(checkIn, checkOut));
        booking.setCheckInDate(checkIn);
        booking.setCheckOutDate(checkOut);
        booking.setStatus("PENDING_PAYMENT");
        booking.setAmountMinor(quote.totalMinor());
        booking.setCurrency(quote.currency());
        booking.setSource("WEB");
        booking.setHoldExpiresAt(now.plusMinutes(HOLD_MINUTES));
        booking.setVersion(0);
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);
        bookingMapper.insert(booking);

        Long customerId = bookedByAccountId == null ? null
                : memberLookupProvider.findMemberId(context.tenantId(), bookedByAccountId).orElse(null);
        booking.setOrderId(orderApplicationService.createHotelReceivable(context.tenantId(),
                context.organizationId(), context.storeId(), booking.getBookingNo(),
                booking.getCurrency(), booking.getAmountMinor(), customerId));
        booking.setBookedByAccountId(bookedByAccountId);
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);

        for (NightQuote quoteNight : quote.nights()) {
            if (inventoryMapper.hold(context.tenantId(), context.storeId(), roomTypeId, quoteNight.stayDate(), rooms) == 0) {
                throw new ApiException(409, "INVENTORY_UNAVAILABLE", "库存已被其他预订占用，请重新报价");
            }
            HotelInventoryHoldPo hold = new HotelInventoryHoldPo();
            hold.setTenantId(context.tenantId());
            hold.setStoreId(context.storeId());
            hold.setBookingId(booking.getId());
            hold.setRoomTypeId(roomTypeId);
            hold.setStayDate(quoteNight.stayDate());
            hold.setRooms(rooms);
            hold.setStatus("HELD");
            hold.setExpiresAt(booking.getHoldExpiresAt());
            hold.setCreatedAt(now);
            hold.setUpdatedAt(now);
            holdMapper.insert(hold);

            HotelBookingNightPo bookingNight = new HotelBookingNightPo();
            bookingNight.setTenantId(context.tenantId());
            bookingNight.setStoreId(context.storeId());
            bookingNight.setBookingId(booking.getId());
            bookingNight.setStayDate(quoteNight.stayDate());
            bookingNight.setPriceMinor(quoteNight.priceMinor());
            bookingNight.setCurrency(booking.getCurrency());
            bookingNight.setStatus("HELD");
            bookingNight.setCreatedAt(now);
            bookingNight.setUpdatedAt(now);
            nightMapper.insert(bookingNight);
        }
        return booking;
    }

    @Transactional
    public HotelBookingPo confirm(Long bookingId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"PENDING_PAYMENT".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能确认");
        }
        if (booking.getHoldExpiresAt() != null && booking.getHoldExpiresAt().isBefore(LocalDateTime.now())) {
            cancel(bookingId);
            throw new ApiException(409, "HOLD_EXPIRED", "库存占用已过期");
        }
        for (HotelInventoryHoldPo hold : holds(bookingId, "HELD")) {
            if (inventoryMapper.confirmHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms()) == 0) {
                throw new ApiException(409, "INVENTORY_CONFIRM_CONFLICT", "库存占用状态冲突");
            }
            hold.setStatus("SOLD");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        updateNights(bookingId, "SOLD");
        booking.setStatus("CONFIRMED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelBookingPo cancel(Long bookingId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if ("CANCELLED".equals(booking.getStatus()) || "CHECKED_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能取消");
        }
        for (HotelInventoryHoldPo hold : holds(bookingId, "HELD")) {
            inventoryMapper.releaseHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        for (HotelInventoryHoldPo hold : holds(bookingId, "SOLD")) {
            inventoryMapper.releaseSold(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        updateNights(bookingId, "CANCELLED");
        booking.setStatus("CANCELLED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelBookingPo noShow(Long bookingId) {
        PermissionGuard.require("hotel.booking.create");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"CONFIRMED".equals(booking.getStatus()) && !"ARRIVAL".equals(booking.getStatus())) {
            throw new ApiException(409, "BOOKING_STATE_INVALID", "当前预订不能标记 no-show");
        }
        for (HotelInventoryHoldPo hold : holds(bookingId, "HELD")) {
            inventoryMapper.releaseHeld(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        for (HotelInventoryHoldPo hold : holds(bookingId, "SOLD")) {
            inventoryMapper.releaseSold(context.tenantId(), context.storeId(), hold.getRoomTypeId(),
                    hold.getStayDate(), hold.getRooms());
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        updateNights(bookingId, "NO_SHOW");
        booking.setStatus("NO_SHOW");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        return booking;
    }

    public List<HotelBookingPo> list(String status, LocalDate from, LocalDate to) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        LambdaQueryWrapper<HotelBookingPo> query = new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .orderByDesc(HotelBookingPo::getId);
        if (status != null && !status.isBlank()) query.eq(HotelBookingPo::getStatus, status);
        if (from != null) query.ge(HotelBookingPo::getCheckInDate, from);
        if (to != null) query.lt(HotelBookingPo::getCheckOutDate, to);
        List<HotelBookingPo> rows = bookingMapper.selectList(query);
        maskGuestPii(rows, context);
        return rows;
    }

    public List<HotelBookingPo> listByAccount(String status, LocalDate from, LocalDate to, Long accountId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        if (accountId == null || accountId <= 0) {
            throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少账号");
        }
        LambdaQueryWrapper<HotelBookingPo> query = new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .eq(HotelBookingPo::getBookedByAccountId, accountId)
                .orderByDesc(HotelBookingPo::getId);
        if (status != null && !status.isBlank()) query.eq(HotelBookingPo::getStatus, status);
        if (from != null) query.ge(HotelBookingPo::getCheckInDate, from);
        if (to != null) query.lt(HotelBookingPo::getCheckOutDate, to);
        List<HotelBookingPo> rows = bookingMapper.selectList(query);
        maskGuestPii(rows, context);
        return rows;
    }


    /** B 端无 member.pii.view 时掩码住客姓名/手机号；C 本人订单保持完整。 */
    private static void maskGuestPii(List<HotelBookingPo> rows, TenantContext context) {
        boolean canViewPii = context.permissions() != null
                && context.permissions().contains("member.pii.view");
        if (canViewPii || rows == null) {
            return;
        }
        for (HotelBookingPo row : rows) {
            if (row.getBookedByAccountId() != null
                    && row.getBookedByAccountId() == context.accountId()) {
                continue;
            }
            row.setGuestName(maskName(row.getGuestName()));
            row.setGuestPhone(maskPhone(row.getGuestPhone()));
        }
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    private List<HotelInventoryHoldPo> holds(Long bookingId, String status) {
        return holdMapper.selectList(new LambdaQueryWrapper<HotelInventoryHoldPo>()
                .eq(HotelInventoryHoldPo::getBookingId, bookingId)
                .eq(HotelInventoryHoldPo::getStatus, status));
    }

    /** 每日房价优先，未配置时回退房价计划基础价。 */
    private long dailyPrice(TenantContext context, Long roomTypeId, HotelRatePlanPo ratePlan, LocalDate date) {
        HotelDailyRatePo daily = dailyRateMapper.selectOne(new LambdaQueryWrapper<HotelDailyRatePo>()
                .eq(HotelDailyRatePo::getStoreId, context.storeId())
                .eq(HotelDailyRatePo::getRoomTypeId, roomTypeId)
                .eq(HotelDailyRatePo::getRatePlanId, ratePlan.getId())
                .eq(HotelDailyRatePo::getStayDate, date));
        return daily == null ? ratePlan.getBasePriceMinor() : daily.getPriceMinor();
    }

    private void updateNights(Long bookingId, String status) {
        for (HotelBookingNightPo night : nightMapper.selectList(new LambdaQueryWrapper<HotelBookingNightPo>()
                .eq(HotelBookingNightPo::getBookingId, bookingId))) {
            night.setStatus(status);
            night.setUpdatedAt(LocalDateTime.now());
            nightMapper.updateById(night);
        }
    }

    /** 未支付 hold 到期释放；调度跨租户，逐条按 hold 自身 tenant/store 释放。 */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void expireHolds() {
        for (HotelInventoryHoldPo hold : holdMapper.selectExpired()) {
            if (holdMapper.releaseExpired(hold.getId()) > 0) {
                inventoryMapper.releaseHeld(hold.getTenantId(), hold.getStoreId(), hold.getRoomTypeId(),
                        hold.getStayDate(), hold.getRooms());
                nightMapper.cancelHeldNights(hold.getBookingId());
                bookingMapper.cancelPending(hold.getBookingId());
            }
        }
    }

    private HotelInventoryPo inventory(TenantContext context, Long roomTypeId, LocalDate date) {        return inventoryMapper.selectOne(new LambdaQueryWrapper<HotelInventoryPo>()
                .eq(HotelInventoryPo::getStoreId, context.storeId())
                .eq(HotelInventoryPo::getRoomTypeId, roomTypeId)
                .eq(HotelInventoryPo::getStayDate, date));
    }

    private HotelBookingPo requireBooking(Long id, TenantContext context) {
        HotelBookingPo booking = bookingMapper.selectById(id);
        if (booking == null || !context.storeId().equals(booking.getStoreId())) {
            throw new ApiException(404, "HOTEL_BOOKING_NOT_FOUND", "酒店预订不存在");
        }
        return booking;
    }

    private HotelRoomTypePo requireRoomType(Long id, TenantContext context) {
        HotelRoomTypePo roomType = roomTypeMapper.selectById(id);
        if (roomType == null || !context.storeId().equals(roomType.getStoreId())) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在");
        }
        return roomType;
    }

    private HotelRatePlanPo requireRatePlan(Long id, Long hotelId, TenantContext context) {
        HotelRatePlanPo ratePlan = id == null ? null : ratePlanMapper.selectById(id);
        if (ratePlan == null) {
            ratePlan = ratePlanMapper.selectOne(new LambdaQueryWrapper<HotelRatePlanPo>()
                    .eq(HotelRatePlanPo::getStoreId, context.storeId())
                    .eq(HotelRatePlanPo::getHotelId, hotelId)
                    .eq(HotelRatePlanPo::getStatus, "ACTIVE")
                    .orderByAsc(HotelRatePlanPo::getId)
                    .last("LIMIT 1"));
        }
        if (ratePlan == null || !context.storeId().equals(ratePlan.getStoreId())
                || !hotelId.equals(ratePlan.getHotelId())) {
            throw new ApiException(404, "RATE_PLAN_NOT_FOUND", "房价计划不存在");
        }
        return ratePlan;
    }

    private void validateStay(LocalDate checkIn, LocalDate checkOut, int rooms) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn) || rooms < 1) {
            throw new ApiException(400, "STAY_INVALID", "入住日期、离店日期或房间数不合法");
        }
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "酒店预订必须在门店上下文操作");
        }
        return context;
    }

    public record NightQuote(LocalDate stayDate, long priceMinor, int available) {}

    public record Quote(boolean available, long totalMinor, String currency, List<NightQuote> nights,
                        LocalDateTime holdExpiresAt) {}
}
