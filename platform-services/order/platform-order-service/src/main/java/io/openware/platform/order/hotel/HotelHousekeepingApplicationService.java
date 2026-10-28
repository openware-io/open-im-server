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

/** 客房清扫任务：PENDING → IN_PROGRESS → DONE；退房清扫开始/完成时推进房间清扫状态。 */
@Service
public class HotelHousekeepingApplicationService {

    /** 退房后的强制清扫：DIRTY → CLEANING → CLEAN。 */
    public static final String TYPE_DEPARTURE = "DEPARTURE";
    /** 在住客人的日常清扫：只结束任务，不改变房间占用和清扫状态。 */
    public static final String TYPE_STAYOVER = "STAYOVER";

    private final HotelHousekeepingMapper taskMapper;
    private final HotelRoomMapper roomMapper;
    private final HotelBookingMapper bookingMapper;
    private final HotelStayMapper stayMapper;

    public HotelHousekeepingApplicationService(HotelHousekeepingMapper taskMapper, HotelRoomMapper roomMapper,
                                                HotelBookingMapper bookingMapper, HotelStayMapper stayMapper) {
        this.taskMapper = taskMapper;
        this.roomMapper = roomMapper;
        this.bookingMapper = bookingMapper;
        this.stayMapper = stayMapper;
    }

    public List<HotelHousekeepingPo> tasks() {
        PermissionGuard.require("hotel.housekeeping.view");
        TenantContext context = requireStore();
        return taskMapper.selectList(new LambdaQueryWrapper<HotelHousekeepingPo>()
                .eq(HotelHousekeepingPo::getStoreId, context.storeId())
                .orderByAsc(HotelHousekeepingPo::getStatus)
                .orderByAsc(HotelHousekeepingPo::getId));
    }

    @Transactional
    public HotelHousekeepingPo start(Long id) {
        PermissionGuard.require("hotel.housekeeping.manage");
        HotelHousekeepingPo task = requireTask(id);
        if (!"PENDING".equals(task.getStatus())) {
            throw new ApiException(409, "HOUSEKEEPING_STATE_INVALID", "清扫任务不是待处理状态");
        }
        TenantContext context = requireStore();
        // 退房清扫开始：空闲脏房 → 清扫中；在住日常清扫不动房态。
        if (TYPE_DEPARTURE.equals(task.getTaskType())) {
            roomMapper.startCleaning(task.getRoomId(), context.tenantId(), context.storeId());
        }
        task.setStatus("IN_PROGRESS");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public HotelHousekeepingPo complete(Long id) {
        PermissionGuard.require("hotel.housekeeping.manage");
        HotelHousekeepingPo task = requireTask(id);
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new ApiException(409, "HOUSEKEEPING_STATE_INVALID", "清扫任务不是进行中状态");
        }
        TenantContext context = requireStore();
        // 在住日常清扫不改变房态；退房清扫完成后 CLEANING（兼容历史 DIRTY）→ CLEAN。
        if (!TYPE_STAYOVER.equals(task.getTaskType())) {
            if (roomMapper.finishCleaning(task.getRoomId(), context.tenantId(), context.storeId()) == 0) {
                throw new ApiException(409, "ROOM_STATE_INVALID", "房间不是待清扫/清扫中状态");
            }
        }
        task.setStatus("DONE");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    /**
     * C 端在住客人申请清扫：只创建 STAYOVER 任务，绝不把在住房间改成 DIRTY。
     *
     * <p>同一预订已有未完成 STAYOVER 任务时幂等返回现有任务，避免客人重复点击产生多条任务。
     */
    @Transactional
    public HotelHousekeepingPo requestStayover(Long bookingId, Long accountId) {
        TenantContext context = requireStore();
        HotelBookingPo booking = bookingMapper.selectById(bookingId);
        if (booking == null || !context.storeId().equals(booking.getStoreId())) {
            throw new ApiException(404, "HOTEL_BOOKING_NOT_FOUND", "酒店预订不存在");
        }
        if (accountId == null || !accountId.equals(booking.getBookedByAccountId())) {
            throw new ApiException(403, "BOOKING_OWNER_REQUIRED", "只能为本人预订申请清扫");
        }
        if (!"CHECKED_IN".equals(booking.getStatus()) && !"DUE_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "HOUSEKEEPING_NOT_APPLICABLE", "只有当前在住客人可以申请日常清扫");
        }
        HotelStayPo stay = stayMapper.selectOne(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getBookingId, bookingId)
                .eq(HotelStayPo::getStatus, "IN_HOUSE")
                .last("LIMIT 1"));
        if (stay == null) {
            throw new ApiException(409, "STAY_NOT_FOUND", "在住记录不存在");
        }
        HotelHousekeepingPo existing = taskMapper.selectOne(new LambdaQueryWrapper<HotelHousekeepingPo>()
                .eq(HotelHousekeepingPo::getBookingId, bookingId)
                .eq(HotelHousekeepingPo::getTaskType, TYPE_STAYOVER)
                .in(HotelHousekeepingPo::getStatus, List.of("PENDING", "IN_PROGRESS"))
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        HotelHousekeepingPo task = new HotelHousekeepingPo();
        task.setTenantId(context.tenantId());
        task.setStoreId(context.storeId());
        task.setRoomId(stay.getRoomId());
        task.setRoomNo(stay.getRoomNo());
        task.setBookingId(bookingId);
        task.setTaskType(TYPE_STAYOVER);
        task.setSource("CUSTOMER");
        task.setNote("住客申请日常清扫");
        task.setStatus("PENDING");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return task;
    }

    private HotelHousekeepingPo requireTask(Long id) {
        TenantContext context = requireStore();
        HotelHousekeepingPo task = taskMapper.selectById(id);
        if (task == null || !context.storeId().equals(task.getStoreId())) {
            throw new ApiException(404, "HOUSEKEEPING_NOT_FOUND", "清扫任务不存在");
        }
        return task;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "客房清扫必须在门店上下文操作");
        }
        return context;
    }
}
