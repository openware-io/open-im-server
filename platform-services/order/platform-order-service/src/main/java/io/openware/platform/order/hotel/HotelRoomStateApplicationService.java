package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 房态聚合：房态图、清扫状态、维修/停售、客房保留。
 *
 * <p>房态由三组字段表达；“维修/停售”通过 htl_room_hold + htl_room_availability 同步调整房量，
 * 房态本身不直接改已售数量。
 */
@Service
public class HotelRoomStateApplicationService {

    private final HotelRoomMapper roomMapper;
    private final HotelRoomTypeMapper roomTypeMapper;
    private final HotelRoomHoldMapper holdMapper;
    private final HotelStayMapper stayMapper;
    private final HotelBookingMapper bookingMapper;
    private final HotelRoomAvailabilityMapper availabilityMapper;
    private final HotelStoreScopeResolver storeScopeResolver;

    public HotelRoomStateApplicationService(HotelRoomMapper roomMapper, HotelRoomTypeMapper roomTypeMapper,
                                            HotelRoomHoldMapper holdMapper, HotelStayMapper stayMapper,
                                            HotelBookingMapper bookingMapper,
                                            HotelRoomAvailabilityMapper availabilityMapper,
                                            HotelStoreScopeResolver storeScopeResolver) {
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.holdMapper = holdMapper;
        this.stayMapper = stayMapper;
        this.bookingMapper = bookingMapper;
        this.availabilityMapper = availabilityMapper;
        this.storeScopeResolver = storeScopeResolver;
    }

    /** 房态图：返回三组房态字段 + 当前在住客人，展示态由前端派生。 */
    public List<HotelRoomBoardView> board() {
        PermissionGuard.require("hotel.room.view");
        TenantContext context = requireStore();
        List<HotelRoomPo> rooms = roomMapper.selectList(new LambdaQueryWrapper<HotelRoomPo>()
                .eq(HotelRoomPo::getStoreId, context.storeId())
                .orderByAsc(HotelRoomPo::getRoomNo));
        Map<Long, String> typeNames = new HashMap<>();
        for (HotelRoomTypePo type : roomTypeMapper.selectList(new LambdaQueryWrapper<HotelRoomTypePo>()
                .eq(HotelRoomTypePo::getStoreId, context.storeId()))) {
            typeNames.put(type.getId(), type.getName());
        }
        Map<Long, HotelRoomHoldPo> holds = guestHoldsByRoom(context);
        Map<Long, HotelStayPo> stays = inHouseStaysByRoom(context);
        Map<Long, HotelBookingPo> bookings = bookingsById(stays.values(), context);
        List<HotelRoomBoardView> board = new ArrayList<>(rooms.size());
        for (HotelRoomPo room : rooms) {
            HotelStayPo stay = stays.get(room.getId());
            HotelBookingPo booking = stay == null ? null : bookings.get(stay.getBookingId());
            HotelRoomHoldPo hold = holds.get(room.getId());
            board.add(new HotelRoomBoardView(room.getId(), room.getRoomNo(), room.getFloor(),
                    room.getRoomTypeId(), typeNames.get(room.getRoomTypeId()),
                    room.getOccupancyStatus(), room.getHousekeepingStatus(), room.getOutOfOrderStatus(),
                    hold == null ? null : hold.getReason(), hold == null ? null : hold.getId(),
                    booking == null ? null : booking.getId(),
                    booking == null ? null : booking.getBookingNo(),
                    booking == null ? null : booking.getGuestName(),
                    booking == null ? null : booking.getCheckInDate(),
                    booking == null ? null : booking.getCheckOutDate()));
        }
        return board;
    }

    /** 清扫状态流转：DIRTY→CLEANING→CLEAN→INSPECTED；在住客房不允许手工改清扫态。 */
    @Transactional
    public HotelRoomPo setHousekeepingStatus(Long roomId, String status) {
        PermissionGuard.require("hotel.room.manage");
        TenantContext context = requireStore();
        if (status == null || !HotelRoomStatePolicy.HOUSEKEEPING_STATUSES.contains(status)) {
            throw new ApiException(400, "ROOM_HOUSEKEEPING_INVALID", "清扫状态只能是 CLEAN/DIRTY/CLEANING/INSPECTED");
        }
        HotelRoomPo room = requireRoom(roomId, context);
        String from = room.getHousekeepingStatus();
        if (status.equals(from)) {
            return room;
        }
        if (HotelRoomStatePolicy.OCCUPANCY_OCCUPIED.equals(room.getOccupancyStatus())) {
            throw new ApiException(409, "ROOM_OCCUPIED", "在住客房不能手工调整清扫状态");
        }
        if (!HotelRoomStatePolicy.isValidHousekeepingTransition(from, status)) {
            throw new ApiException(409, "ROOM_HOUSEKEEPING_TRANSITION_INVALID", "当前清扫状态不能流转到目标状态");
        }
        int changed = switch (status) {
            case HotelRoomStatePolicy.HOUSEKEEPING_CLEANING -> roomMapper.startCleaning(roomId,
                    context.tenantId(), context.storeId());
            case HotelRoomStatePolicy.HOUSEKEEPING_CLEAN -> roomMapper.finishCleaning(roomId,
                    context.tenantId(), context.storeId());
            case HotelRoomStatePolicy.HOUSEKEEPING_INSPECTED -> roomMapper.inspect(roomId,
                    context.tenantId(), context.storeId());
            case HotelRoomStatePolicy.HOUSEKEEPING_DIRTY -> roomMapper.markDirty(roomId,
                    context.tenantId(), context.storeId());
            default -> 0;
        };
        if (changed == 0) {
            throw new ApiException(409, "ROOM_HOUSEKEEPING_CONFLICT", "房态已被其他操作修改，请刷新后重试");
        }
        return requireRoom(roomId, context);
    }

    /**
     * 维修/停售：设置时按日期逐天下调房型房量，恢复时加回；已入住客房不能设置。
     * 这是“维修通过服务调整房量”的唯一入口，房态写入本身不改 sold。
     */
    @Transactional
    public HotelRoomPo setOutOfOrder(Long roomId, String status, LocalDate from, LocalDate to) {
        PermissionGuard.require("hotel.room.manage");
        TenantContext context = requireStore();
        if (status == null || !HotelRoomStatePolicy.OUT_OF_ORDER_STATUSES.contains(status)) {
            throw new ApiException(400, "ROOM_OUT_OF_ORDER_INVALID", "不可售状态只能是 NONE/OUT_OF_ORDER/OUT_OF_SERVICE");
        }
        HotelRoomPo room = requireRoom(roomId, context);
        clearOutOfOrder(room, context);
        if (HotelRoomStatePolicy.OUT_OF_ORDER_NONE.equals(status)) {
            return requireRoom(roomId, context);
        }
        if (HotelRoomStatePolicy.OCCUPANCY_OCCUPIED.equals(room.getOccupancyStatus())) {
            throw new ApiException(409, "ROOM_OCCUPIED", "在住客房不能设置维修/停售");
        }
        StoreScopeRepository.StoreScope storeScope = storeScopeResolver.requireHotelScope(context);
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw new ApiException(400, "ROOM_OUT_OF_ORDER_DATE_INVALID", "停售结束日期不能早于开始日期");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new ApiException(400, "ROOM_OUT_OF_ORDER_DATE_INVALID", "停售日期区间不能超过一年");
        }
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (availabilityMapper.adjustTotal(context.tenantId(), context.storeId(),
                    room.getRoomTypeId(), date, -1) == 0 && availabilityExists(context, room.getRoomTypeId(), date)) {
                throw new ApiException(409, "ROOM_AVAILABILITY_CONFLICT",
                        date + " 房量已售完或未配置，不能把客房设为维修/停售");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        HotelRoomHoldPo hold = new HotelRoomHoldPo();
        hold.setTenantId(context.tenantId());
        hold.setOrganizationId(storeScope.organizationId());
        hold.setStoreId(context.storeId());
        hold.setRoomId(roomId);
        hold.setReason(HotelRoomStatePolicy.OUT_OF_SERVICE.equals(status)
                ? HotelRoomStatePolicy.HOLD_OUT_OF_SERVICE : HotelRoomStatePolicy.HOLD_MAINTENANCE);
        hold.setStartAt(start.atStartOfDay());
        hold.setEndAt(end.plusDays(1).atStartOfDay());
        hold.setStatus("ACTIVE");
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        holdMapper.insert(hold);
        if (roomMapper.setOutOfOrderStatus(roomId, context.tenantId(), context.storeId(), status) == 0) {
            throw new ApiException(409, "ROOM_OCCUPIED", "客房已被入住占用，无法设置维修/停售");
        }
        return requireRoom(roomId, context);
    }

    /** 保留客房：RESERVED/GROUP_BLOCK/HOUSE_USE，保留期间不可售、不可入住。 */
    @Transactional
    public HotelRoomHoldPo addHold(Long roomId, String reason, Long bookingId, LocalDate from, LocalDate to) {
        PermissionGuard.require("hotel.room.manage");
        TenantContext context = requireStore();
        if (reason == null || !HotelRoomStatePolicy.GUEST_HOLD_REASONS.contains(reason)) {
            throw new ApiException(400, "ROOM_HOLD_REASON_INVALID", "保留原因只能是 RESERVED/GROUP_BLOCK/HOUSE_USE/LOCKED");
        }
        HotelRoomPo room = requireRoom(roomId, context);
        if (HotelRoomStatePolicy.OCCUPANCY_OCCUPIED.equals(room.getOccupancyStatus())) {
            throw new ApiException(409, "ROOM_OCCUPIED", "在住客房不能设置保留");
        }
        if (HotelRoomStatePolicy.outOfOrder(room.getOutOfOrderStatus())) {
            throw new ApiException(409, "ROOM_OUT_OF_ORDER", "维修/停售客房不能设置保留");
        }
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) {
            throw new ApiException(400, "ROOM_HOLD_DATE_INVALID", "保留结束日期不能早于开始日期");
        }
        StoreScopeRepository.StoreScope storeScope = storeScopeResolver.requireHotelScope(context);
        for (HotelRoomHoldPo active : activeHolds(roomId, context)) {
            LocalDate activeStart = active.getStartAt() == null ? null : active.getStartAt().toLocalDate();
            LocalDate activeEnd = active.getEndAt() == null ? null : active.getEndAt().toLocalDate().minusDays(1);
            if (activeStart != null && activeEnd != null && !end.isBefore(activeStart) && !start.isAfter(activeEnd)) {
                throw new ApiException(409, "ROOM_HOLD_CONFLICT", "该客房在所选日期已有保留/不可售记录");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        HotelRoomHoldPo hold = new HotelRoomHoldPo();
        hold.setTenantId(context.tenantId());
        hold.setOrganizationId(storeScope.organizationId());
        hold.setStoreId(context.storeId());
        hold.setRoomId(roomId);
        hold.setBookingId(bookingId);
        hold.setReason(reason);
        hold.setStartAt(start.atStartOfDay());
        hold.setEndAt(end.plusDays(1).atStartOfDay());
        hold.setStatus("ACTIVE");
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        holdMapper.insert(hold);
        return hold;
    }

    /** 释放保留；维修/停售占用只能走 setOutOfOrder(NONE) 恢复，避免房量漏加。 */
    @Transactional
    public HotelRoomHoldPo releaseHold(Long roomId, Long holdId) {
        PermissionGuard.require("hotel.room.manage");
        TenantContext context = requireStore();
        requireRoom(roomId, context);
        HotelRoomHoldPo hold = holdMapper.selectById(holdId);
        if (hold == null || !context.storeId().equals(hold.getStoreId()) || !roomId.equals(hold.getRoomId())) {
            throw new ApiException(404, "ROOM_HOLD_NOT_FOUND", "客房保留记录不存在");
        }
        if (!"ACTIVE".equals(hold.getStatus())) {
            return hold;
        }
        if (HotelRoomStatePolicy.OUT_OF_ORDER_HOLD_REASONS.contains(hold.getReason())) {
            throw new ApiException(409, "ROOM_OUT_OF_ORDER_ACTIVE", "维修/停售占用请通过恢复可售操作释放");
        }
        hold.setStatus("RELEASED");
        hold.setUpdatedAt(LocalDateTime.now());
        holdMapper.updateById(hold);
        return hold;
    }

    public List<HotelRoomHoldPo> holds(Long roomId) {
        PermissionGuard.require("hotel.room.view");
        TenantContext context = requireStore();
        requireRoom(roomId, context);
        return holdMapper.selectList(new LambdaQueryWrapper<HotelRoomHoldPo>()
                .eq(HotelRoomHoldPo::getStoreId, context.storeId())
                .eq(HotelRoomHoldPo::getRoomId, roomId)
                .orderByDesc(HotelRoomHoldPo::getId));
    }

    private void clearOutOfOrder(HotelRoomPo room, TenantContext context) {
        if (!HotelRoomStatePolicy.outOfOrder(room.getOutOfOrderStatus())) {
            return;
        }
        for (HotelRoomHoldPo hold : activeHolds(room.getId(), context)) {
            if (!HotelRoomStatePolicy.OUT_OF_ORDER_HOLD_REASONS.contains(hold.getReason())) {
                continue;
            }
            LocalDate start = hold.getStartAt() == null ? null : hold.getStartAt().toLocalDate();
            LocalDate endExclusive = hold.getEndAt() == null ? null : hold.getEndAt().toLocalDate();
            if (start != null && endExclusive != null) {
                for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
                    availabilityMapper.adjustTotal(context.tenantId(), context.storeId(),
                            room.getRoomTypeId(), date, 1);
                }
            }
            hold.setStatus("RELEASED");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        roomMapper.clearOutOfOrderStatus(room.getId(), context.tenantId(), context.storeId());
    }

    private List<HotelRoomHoldPo> activeHolds(Long roomId, TenantContext context) {
        return holdMapper.selectActiveByRoom(context.tenantId(), context.storeId(), roomId);
    }

    private Map<Long, HotelRoomHoldPo> guestHoldsByRoom(TenantContext context) {
        Map<Long, HotelRoomHoldPo> byRoom = new HashMap<>();
        for (HotelRoomHoldPo hold : holdMapper.selectActiveByStore(context.tenantId(), context.storeId())) {
            if (HotelRoomStatePolicy.GUEST_HOLD_REASONS.contains(hold.getReason())) {
                byRoom.putIfAbsent(hold.getRoomId(), hold);
            }
        }
        return byRoom;
    }

    private Map<Long, HotelStayPo> inHouseStaysByRoom(TenantContext context) {
        List<HotelStayPo> stays = stayMapper.selectList(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getStoreId, context.storeId())
                .eq(HotelStayPo::getStatus, "IN_HOUSE")
                .orderByDesc(HotelStayPo::getId));
        Map<Long, HotelStayPo> byRoom = new HashMap<>();
        for (HotelStayPo stay : stays) {
            byRoom.putIfAbsent(stay.getRoomId(), stay);
        }
        return byRoom;
    }

    private Map<Long, HotelBookingPo> bookingsById(java.util.Collection<HotelStayPo> stays,
                                                   TenantContext context) {
        Set<Long> ids = new HashSet<>();
        for (HotelStayPo stay : stays) {
            if (stay.getBookingId() != null) {
                ids.add(stay.getBookingId());
            }
        }
        Map<Long, HotelBookingPo> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (HotelBookingPo booking : bookingMapper.selectByIds(ids)) {
            if (context.storeId().equals(booking.getStoreId())) {
                byId.put(booking.getId(), booking);
            }
        }
        return byId;
    }

    private boolean availabilityExists(TenantContext context, Long roomTypeId, LocalDate stayDate) {
        return availabilityMapper.selectCount(new LambdaQueryWrapper<HotelRoomAvailabilityPo>()
                .eq(HotelRoomAvailabilityPo::getStoreId, context.storeId())
                .eq(HotelRoomAvailabilityPo::getRoomTypeId, roomTypeId)
                .eq(HotelRoomAvailabilityPo::getStayDate, stayDate)) > 0;
    }

    private HotelRoomPo requireRoom(Long roomId, TenantContext context) {
        HotelRoomPo room = roomMapper.selectById(roomId);
        if (room == null || !context.storeId().equals(room.getStoreId())) {
            throw new ApiException(404, "ROOM_NOT_FOUND", "房间不存在");
        }
        return room;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "房态操作必须在门店上下文进行");
        }
        return context;
    }
}
