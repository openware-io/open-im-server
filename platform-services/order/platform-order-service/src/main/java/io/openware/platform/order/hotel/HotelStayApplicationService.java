package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 入住分房、押金、退房结算与客房清扫任务。 */
@Service
public class HotelStayApplicationService {

    private final HotelBookingMapper bookingMapper;
    private final HotelRoomMapper roomMapper;
    private final HotelStayMapper stayMapper;
    private final HotelFolioMapper folioMapper;
    private final HotelFolioItemMapper folioItemMapper;
    private final HotelHousekeepingMapper housekeepingMapper;
    private final HotelRefundMapper refundMapper;
    private final HotelPaymentRefundClient paymentRefundClient;
    private final HotelInventoryMapper inventoryMapper;
    private final HotelRatePlanMapper ratePlanMapper;
    private final HotelDailyRateMapper dailyRateMapper;
    private final HotelBookingNightMapper nightMapper;

    public HotelStayApplicationService(HotelBookingMapper bookingMapper, HotelRoomMapper roomMapper,
                                       HotelStayMapper stayMapper, HotelFolioMapper folioMapper,
                                       HotelFolioItemMapper folioItemMapper,
                                       HotelHousekeepingMapper housekeepingMapper,
                                       HotelRefundMapper refundMapper,
                                       HotelPaymentRefundClient paymentRefundClient,
                                       HotelInventoryMapper inventoryMapper,
                                       HotelRatePlanMapper ratePlanMapper,
                                       HotelDailyRateMapper dailyRateMapper,
                                       HotelBookingNightMapper nightMapper) {
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.stayMapper = stayMapper;
        this.folioMapper = folioMapper;
        this.folioItemMapper = folioItemMapper;
        this.housekeepingMapper = housekeepingMapper;
        this.refundMapper = refundMapper;
        this.paymentRefundClient = paymentRefundClient;
        this.inventoryMapper = inventoryMapper;
        this.ratePlanMapper = ratePlanMapper;
        this.dailyRateMapper = dailyRateMapper;
        this.nightMapper = nightMapper;
    }

    @Transactional
    public HotelBookingPo checkIn(Long bookingId) {
        return checkIn(bookingId, null);
    }

    /** 办理入住；roomId 为空时按房型自动分配，传入时使用前台选择的房间。 */
    @Transactional
    public HotelBookingPo checkIn(Long bookingId, Long roomId) {
        PermissionGuard.require("hotel.checkin");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"CONFIRMED".equals(booking.getStatus()) && !"ARRIVAL".equals(booking.getStatus())) {
            throw new ApiException(409, "CHECKIN_STATE_INVALID", "当前预订不能办理入住");
        }
        if (stayMapper.selectOne(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getBookingId, bookingId)) != null) {
            throw new ApiException(409, "STAY_ALREADY_EXISTS", "该预订已办理入住");
        }
        HotelRoomPo room;
        if (roomId == null) {
            room = roomMapper.selectOne(new LambdaQueryWrapper<HotelRoomPo>()
                    .eq(HotelRoomPo::getStoreId, context.storeId())
                    .eq(HotelRoomPo::getRoomTypeId, booking.getRoomTypeId())
                    .eq(HotelRoomPo::getStatus, "ACTIVE")
                    .orderByAsc(HotelRoomPo::getRoomNo)
                    .last("LIMIT 1"));
        } else {
            room = roomMapper.selectOne(new LambdaQueryWrapper<HotelRoomPo>()
                    .eq(HotelRoomPo::getId, roomId)
                    .eq(HotelRoomPo::getStoreId, context.storeId())
                    .eq(HotelRoomPo::getRoomTypeId, booking.getRoomTypeId())
                    .eq(HotelRoomPo::getStatus, "ACTIVE"));
        }
        if (room == null || roomMapper.occupy(room.getId(), context.tenantId(), context.storeId()) == 0) {
            throw new ApiException(409, "ROOM_UNAVAILABLE", "没有可分配的房间");
        }
        LocalDateTime now = LocalDateTime.now();
        HotelStayPo stay = new HotelStayPo();
        stay.setTenantId(context.tenantId());
        stay.setStoreId(context.storeId());
        stay.setBookingId(bookingId);
        stay.setRoomId(room.getId());
        stay.setRoomNo(room.getRoomNo());
        stay.setCheckInAt(now);
        stay.setStatus("IN_HOUSE");
        stay.setCreatedAt(now);
        stay.setUpdatedAt(now);
        stayMapper.insert(stay);

        HotelFolioPo folio = new HotelFolioPo();
        folio.setTenantId(context.tenantId());
        folio.setStoreId(context.storeId());
        folio.setBookingId(bookingId);
        folio.setStayId(stay.getId());
        folio.setStatus("OPEN");
        folio.setCurrency(booking.getCurrency());
        folio.setChargeMinor(booking.getAmountMinor() == null ? 0 : booking.getAmountMinor());
        folio.setPaidMinor(0L);
        folio.setCreatedAt(now);
        folio.setUpdatedAt(now);
        folioMapper.insert(folio);
        if (folio.getChargeMinor() > 0) {
            addItem(folio, "ROOM", null, null, "房费", folio.getChargeMinor());
        }

        booking.setRoomNo(room.getRoomNo());
        booking.setStatus("IN_HOUSE");
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelFolioPo collectDeposit(Long bookingId, Long amountMinor, String note) {
        PermissionGuard.require("hotel.checkin");
        TenantContext context = requireStore();
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(400, "DEPOSIT_AMOUNT_INVALID", "押金金额必须大于 0");
        }
        addItem(folio, "DEPOSIT", "CASH", null, note == null ? "押金" : note, amountMinor);
        folio.setPaidMinor(folio.getPaidMinor() + amountMinor);
        folio.setUpdatedAt(LocalDateTime.now());
        folioMapper.updateById(folio);
        return folio;
    }

    @Transactional
    public HotelBookingPo checkOut(Long bookingId) {
        PermissionGuard.require("hotel.checkout");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"IN_HOUSE".equals(booking.getStatus()) && !"DUE_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "CHECKOUT_STATE_INVALID", "当前预订不能退房");
        }
        HotelStayPo stay = stayMapper.selectOne(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getBookingId, bookingId)
                .eq(HotelStayPo::getStatus, "IN_HOUSE"));
        if (stay == null) {
            throw new ApiException(409, "STAY_NOT_FOUND", "在住记录不存在");
        }
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        if (folio.getPaidMinor() < folio.getChargeMinor()) {
            long prepaid = booking.getOrderId() == null ? 0L
                    : java.util.Optional.ofNullable(
                            bookingMapper.selectOrderPaidAmount(booking.getOrderId())).orElse(0L);
            if (prepaid < folio.getChargeMinor()) {
                throw new ApiException(409, "OUTSTANDING_BALANCE", "账单未结清，不能退房");
            }
            addItem(folio, "PAYMENT", "ORDER_PREPAID", String.valueOf(booking.getOrderId()),
                    "订单预付抵扣", -prepaid);
            folio.setPaidMinor(folio.getChargeMinor());
        }
        LocalDateTime now = LocalDateTime.now();
        stay.setStatus("CHECKED_OUT");
        stay.setCheckOutAt(now);
        stay.setUpdatedAt(now);
        stayMapper.updateById(stay);
        folio.setStatus("SETTLED");
        folio.setUpdatedAt(now);
        folioMapper.updateById(folio);
        roomMapper.markDirty(stay.getRoomId(), context.tenantId(), context.storeId());

        HotelHousekeepingPo task = new HotelHousekeepingPo();
        task.setTenantId(context.tenantId());
        task.setStoreId(context.storeId());
        task.setRoomId(stay.getRoomId());
        task.setRoomNo(stay.getRoomNo());
        task.setBookingId(bookingId);
        task.setStatus("PENDING");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        housekeepingMapper.insert(task);

        booking.setStatus("CHECKED_OUT");
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);
        return booking;
    }

    /** 换房：新房间原子占用，旧房间置 DIRTY 并生成清扫任务。 */
    @Transactional
    public HotelBookingPo moveRoom(Long bookingId, Long newRoomId) {
        PermissionGuard.require("hotel.checkin");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"IN_HOUSE".equals(booking.getStatus())) {
            throw new ApiException(409, "MOVE_STATE_INVALID", "只有在住预订可以换房");
        }
        HotelStayPo stay = stayMapper.selectOne(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getBookingId, bookingId)
                .eq(HotelStayPo::getStatus, "IN_HOUSE"));
        if (stay == null) {
            throw new ApiException(409, "STAY_NOT_FOUND", "在住记录不存在");
        }
        if (stay.getRoomId().equals(newRoomId)) {
            return booking;
        }
        HotelRoomPo newRoom = roomMapper.selectById(newRoomId);
        if (newRoom == null || !context.storeId().equals(newRoom.getStoreId())) {
            throw new ApiException(404, "ROOM_NOT_FOUND", "房间不存在");
        }
        if (roomMapper.occupy(newRoomId, context.tenantId(), context.storeId()) == 0) {
            throw new ApiException(409, "ROOM_UNAVAILABLE", "新房间不可用");
        }
        LocalDateTime now = LocalDateTime.now();
        roomMapper.markDirty(stay.getRoomId(), context.tenantId(), context.storeId());
        HotelHousekeepingPo task = new HotelHousekeepingPo();
        task.setTenantId(context.tenantId());
        task.setStoreId(context.storeId());
        task.setRoomId(stay.getRoomId());
        task.setRoomNo(stay.getRoomNo());
        task.setBookingId(bookingId);
        task.setStatus("PENDING");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        housekeepingMapper.insert(task);

        stay.setRoomId(newRoom.getId());
        stay.setRoomNo(newRoom.getRoomNo());
        stay.setUpdatedAt(now);
        stayMapper.updateById(stay);
        booking.setRoomNo(newRoom.getRoomNo());
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelFolioPo refundDeposit(Long bookingId, Long amountMinor, String reason, String method) {
        PermissionGuard.require("hotel.checkout");
        TenantContext context = requireStore();
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(400, "REFUND_AMOUNT_INVALID", "退款金额必须大于 0");
        }
        if (amountMinor > folio.getPaidMinor()) {
            throw new ApiException(409, "REFUND_EXCEEDS_PAID", "退款金额不能超过已收金额");
        }
        String refundMethod = method == null || method.isBlank() ? "CASH" : method.toUpperCase();
        if ("ONLINE".equals(refundMethod)) {
            throw new ApiException(409, "ONLINE_REFUND_NOT_READY",
                    "线上退款待接入 payment-service，请走现金退款或等待支付退款接口");
        }
        if (!"CASH".equals(refundMethod)) {
            throw new ApiException(400, "REFUND_METHOD_INVALID", "退款方式只支持 CASH/ONLINE");
        }
        addItem(folio, "REFUND", "CASH", null, reason == null ? "现金押金退款" : reason, -amountMinor);
        folio.setPaidMinor(folio.getPaidMinor() - amountMinor);
        folio.setUpdatedAt(LocalDateTime.now());
        folioMapper.updateById(folio);
        return folio;
    }

    /** 线上退款只提交 payment-service 申请，保持 PENDING，不减少已收、不记 REFUND 账单项。 */
    @Transactional
    public HotelRefundPo requestOnlineRefund(Long bookingId, Long amountMinor, String reason, Long requestedBy) {
        PermissionGuard.require("hotel.checkout");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        if (booking.getOrderId() == null) {
            throw new ApiException(409, "ORDER_RECEIVABLE_REQUIRED", "预订没有订单应收，不能提交线上退款");
        }
        if (amountMinor == null || amountMinor <= 0 || amountMinor > folio.getPaidMinor()) {
            throw new ApiException(400, "REFUND_AMOUNT_INVALID", "退款金额必须大于 0 且不超过已收");
        }
        LocalDateTime now = LocalDateTime.now();
        HotelRefundPo refund = new HotelRefundPo();
        refund.setTenantId(context.tenantId());
        refund.setStoreId(context.storeId());
        refund.setBookingId(bookingId);
        refund.setFolioId(folio.getId());
        refund.setOrderId(booking.getOrderId());
        refund.setAmountMinor(amountMinor);
        refund.setCurrency(folio.getCurrency());
        refund.setMethod("ONLINE");
        refund.setStatus("PENDING");
        refund.setReason(reason);
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        refundMapper.insert(refund);
        refund.setExternalRef(paymentRefundClient.requestRefund(booking.getOrderId(), amountMinor, reason, requestedBy));
        refund.setUpdatedAt(LocalDateTime.now());
        refundMapper.updateById(refund);
        return refund;
    }

    /** 续住：只对新增夜追加 sold、房费和账单项，不改已住夜。 */
    @Transactional
    public HotelBookingPo extendStay(Long bookingId, LocalDate newCheckOutDate) {
        PermissionGuard.require("hotel.checkin");
        TenantContext context = requireStore();
        HotelBookingPo booking = requireBooking(bookingId, context);
        if (!"IN_HOUSE".equals(booking.getStatus()) && !"DUE_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "EXTEND_STATE_INVALID", "只有在住预订可以续住");
        }
        if (newCheckOutDate == null || !newCheckOutDate.isAfter(booking.getCheckOutDate())) {
            throw new ApiException(400, "EXTEND_DATE_INVALID", "续住日期必须晚于当前离店日期");
        }
        HotelRatePlanPo ratePlan = ratePlanMapper.selectById(booking.getRatePlanId());
        if (ratePlan == null || !context.storeId().equals(ratePlan.getStoreId())) {
            throw new ApiException(409, "RATE_PLAN_NOT_FOUND", "房价计划不存在");
        }
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        long additional = 0;
        LocalDateTime now = LocalDateTime.now();
        for (LocalDate date = booking.getCheckOutDate(); date.isBefore(newCheckOutDate); date = date.plusDays(1)) {
            long price = dailyPrice(context, booking, ratePlan, date);
            if (inventoryMapper.addSold(context.tenantId(), context.storeId(), booking.getRoomTypeId(), date,
                    booking.getRooms()) == 0) {
                throw new ApiException(409, "INVENTORY_UNAVAILABLE", "续住日期库存不足");
            }
            HotelBookingNightPo night = new HotelBookingNightPo();
            night.setTenantId(context.tenantId());
            night.setStoreId(context.storeId());
            night.setBookingId(bookingId);
            night.setStayDate(date);
            night.setPriceMinor(price);
            night.setCurrency(booking.getCurrency());
            night.setStatus("SOLD");
            night.setCreatedAt(now);
            night.setUpdatedAt(now);
            nightMapper.insert(night);
            additional = Math.addExact(additional, Math.multiplyExact(price, booking.getRooms()));
        }
        booking.setCheckOutDate(newCheckOutDate);
        booking.setNights((int) ChronoUnit.DAYS.between(booking.getCheckInDate(), newCheckOutDate));
        booking.setAmountMinor(booking.getAmountMinor() + additional);
        booking.setUpdatedAt(now);
        bookingMapper.updateById(booking);
        folio.setChargeMinor(folio.getChargeMinor() + additional);
        folio.setUpdatedAt(now);
        folioMapper.updateById(folio);
        addItem(folio, "ROOM", null, null, "续住房费", additional);
        return booking;
    }

    private long dailyPrice(TenantContext context, HotelBookingPo booking, HotelRatePlanPo ratePlan, LocalDate date) {
        HotelDailyRatePo daily = dailyRateMapper.selectOne(new LambdaQueryWrapper<HotelDailyRatePo>()
                .eq(HotelDailyRatePo::getStoreId, context.storeId())
                .eq(HotelDailyRatePo::getRoomTypeId, booking.getRoomTypeId())
                .eq(HotelDailyRatePo::getRatePlanId, ratePlan.getId())
                .eq(HotelDailyRatePo::getStayDate, date));
        return daily == null ? ratePlan.getBasePriceMinor() : daily.getPriceMinor();
    }

    /** 同步 payment-service 退款审批/打款状态；REFUNDED 才写账本并减少已收。 */
    @Transactional
    public List<HotelRefundPo> syncOnlineRefunds() {        PermissionGuard.require("hotel.checkout");
        TenantContext context = requireStore();
        List<HotelRefundPo> refunds = refundMapper.selectList(new LambdaQueryWrapper<HotelRefundPo>()
                .eq(HotelRefundPo::getStoreId, context.storeId())
                .eq(HotelRefundPo::getMethod, "ONLINE")
                .in(HotelRefundPo::getStatus, List.of("PENDING", "APPROVED")));
        for (HotelRefundPo refund : refunds) {
            HotelPaymentRefundClient.RefundState state = paymentRefundClient.status(refund.getExternalRef());
            if (state == null || state.status() == null || state.status().isBlank()) {
                continue;
            }
            if ("APPROVED".equals(state.status()) && "PENDING".equals(refund.getStatus())) {
                refund.setStatus("APPROVED");
                refund.setUpdatedAt(LocalDateTime.now());
                refundMapper.updateById(refund);
                continue;
            }
            if ("REJECTED".equals(state.status())) {
                refund.setStatus("REJECTED");
                refund.setUpdatedAt(LocalDateTime.now());
                refundMapper.updateById(refund);
                continue;
            }
            if (!"REFUNDED".equals(state.status())) {
                continue;
            }
            HotelFolioPo folio = folioMapper.selectById(refund.getFolioId());
            if (folio == null || !"OPEN".equals(folio.getStatus())) {
                continue;
            }
            if (refund.getAmountMinor() > folio.getPaidMinor()) {
                continue;
            }
            addItem(folio, "REFUND", "ONLINE", refund.getExternalRef(), "线上押金退款",
                    -refund.getAmountMinor());
            folio.setPaidMinor(folio.getPaidMinor() - refund.getAmountMinor());
            folio.setUpdatedAt(LocalDateTime.now());
            folioMapper.updateById(folio);
            refund.setStatus("REFUNDED");
            refund.setUpdatedAt(LocalDateTime.now());
            refundMapper.updateById(refund);
        }
        return refundMapper.selectList(new LambdaQueryWrapper<HotelRefundPo>()
                .eq(HotelRefundPo::getStoreId, context.storeId())
                .orderByDesc(HotelRefundPo::getId));
    }

    public List<HotelRefundPo> listOnlineRefunds() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        return refundMapper.selectList(new LambdaQueryWrapper<HotelRefundPo>()
                .eq(HotelRefundPo::getStoreId, context.storeId())
                .orderByDesc(HotelRefundPo::getId));
    }

    public FolioView folio(Long bookingId) {        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        HotelFolioPo folio = folioMapper.selectOne(new LambdaQueryWrapper<HotelFolioPo>()
                .eq(HotelFolioPo::getBookingId, bookingId)
                .eq(HotelFolioPo::getStoreId, context.storeId()));
        if (folio == null) {
            throw new ApiException(404, "FOLIO_NOT_FOUND", "账单不存在");
        }
        List<HotelFolioItemPo> items = folioItemMapper.selectList(new LambdaQueryWrapper<HotelFolioItemPo>()
                .eq(HotelFolioItemPo::getFolioId, folio.getId())
                .orderByAsc(HotelFolioItemPo::getId));
        return new FolioView(folio, items);
    }

    private void addItem(HotelFolioPo folio, String type, String method, String reference,
                         String description, long amountMinor) {
        HotelFolioItemPo item = new HotelFolioItemPo();
        item.setTenantId(folio.getTenantId());
        item.setStoreId(folio.getStoreId());
        item.setFolioId(folio.getId());
        item.setType(type);
        item.setMethod(method);
        item.setReference(reference);
        item.setDescription(description);
        item.setAmountMinor(amountMinor);
        item.setCurrency(folio.getCurrency());
        item.setCreatedAt(LocalDateTime.now());
        folioItemMapper.insert(item);
    }

    private HotelFolioPo requireOpenFolio(Long bookingId, TenantContext context) {
        HotelFolioPo folio = folioMapper.selectOne(new LambdaQueryWrapper<HotelFolioPo>()
                .eq(HotelFolioPo::getBookingId, bookingId)
                .eq(HotelFolioPo::getStoreId, context.storeId()));
        if (folio == null || !"OPEN".equals(folio.getStatus())) {
            throw new ApiException(409, "FOLIO_STATE_INVALID", "账单不存在或已结算");
        }
        return folio;
    }

    private HotelBookingPo requireBooking(Long id, TenantContext context) {
        HotelBookingPo booking = bookingMapper.selectById(id);
        if (booking == null || !context.storeId().equals(booking.getStoreId())) {
            throw new ApiException(404, "HOTEL_BOOKING_NOT_FOUND", "酒店预订不存在");
        }
        return booking;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "酒店前台必须在门店上下文操作");
        }
        return context;
    }

    public record FolioView(HotelFolioPo folio, List<HotelFolioItemPo> items) {}
}
