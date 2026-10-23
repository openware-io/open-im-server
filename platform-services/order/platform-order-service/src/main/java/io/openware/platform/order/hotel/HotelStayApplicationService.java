package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDateTime;
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

    public HotelStayApplicationService(HotelBookingMapper bookingMapper, HotelRoomMapper roomMapper,
                                       HotelStayMapper stayMapper, HotelFolioMapper folioMapper,
                                       HotelFolioItemMapper folioItemMapper,
                                       HotelHousekeepingMapper housekeepingMapper) {
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.stayMapper = stayMapper;
        this.folioMapper = folioMapper;
        this.folioItemMapper = folioItemMapper;
        this.housekeepingMapper = housekeepingMapper;
    }

    @Transactional
    public HotelBookingPo checkIn(Long bookingId) {
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
        HotelRoomPo room = roomMapper.selectOne(new LambdaQueryWrapper<HotelRoomPo>()
                .eq(HotelRoomPo::getStoreId, context.storeId())
                .eq(HotelRoomPo::getRoomTypeId, booking.getRoomTypeId())
                .eq(HotelRoomPo::getStatus, "ACTIVE")
                .orderByAsc(HotelRoomPo::getRoomNo)
                .last("LIMIT 1"));
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
            addItem(folio, "ROOM", "房费", folio.getChargeMinor());
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
        addItem(folio, "DEPOSIT", note == null ? "押金" : note, amountMinor);
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
            throw new ApiException(409, "OUTSTANDING_BALANCE", "账单未结清，不能退房");
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
    public HotelFolioPo refundDeposit(Long bookingId, Long amountMinor, String reason) {
        PermissionGuard.require("hotel.checkout");
        TenantContext context = requireStore();
        HotelFolioPo folio = requireOpenFolio(bookingId, context);
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(400, "REFUND_AMOUNT_INVALID", "退款金额必须大于 0");
        }
        if (amountMinor > folio.getPaidMinor()) {
            throw new ApiException(409, "REFUND_EXCEEDS_PAID", "退款金额不能超过已收金额");
        }
        addItem(folio, "REFUND", reason == null ? "押金退款" : reason, -amountMinor);
        folio.setPaidMinor(folio.getPaidMinor() - amountMinor);
        folio.setUpdatedAt(LocalDateTime.now());
        folioMapper.updateById(folio);
        return folio;
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

    private void addItem(HotelFolioPo folio, String type, String description, long amountMinor) {
        HotelFolioItemPo item = new HotelFolioItemPo();
        item.setTenantId(folio.getTenantId());
        item.setStoreId(folio.getStoreId());
        item.setFolioId(folio.getId());
        item.setType(type);
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
