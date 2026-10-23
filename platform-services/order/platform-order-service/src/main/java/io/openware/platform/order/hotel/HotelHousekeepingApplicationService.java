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

/** 客房清扫任务：PENDING → IN_PROGRESS → DONE；完成后房间 DIRTY → ACTIVE。 */
@Service
public class HotelHousekeepingApplicationService {

    private final HotelHousekeepingMapper taskMapper;
    private final HotelRoomMapper roomMapper;

    public HotelHousekeepingApplicationService(HotelHousekeepingMapper taskMapper, HotelRoomMapper roomMapper) {
        this.taskMapper = taskMapper;
        this.roomMapper = roomMapper;
    }

    public List<HotelHousekeepingPo> tasks() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        return taskMapper.selectList(new LambdaQueryWrapper<HotelHousekeepingPo>()
                .eq(HotelHousekeepingPo::getStoreId, context.storeId())
                .orderByAsc(HotelHousekeepingPo::getStatus)
                .orderByAsc(HotelHousekeepingPo::getId));
    }

    @Transactional
    public HotelHousekeepingPo start(Long id) {
        PermissionGuard.require("hotel.inventory.manage");
        HotelHousekeepingPo task = requireTask(id);
        if (!"PENDING".equals(task.getStatus())) {
            throw new ApiException(409, "HOUSEKEEPING_STATE_INVALID", "清扫任务不是待处理状态");
        }
        task.setStatus("IN_PROGRESS");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public HotelHousekeepingPo complete(Long id) {
        PermissionGuard.require("hotel.inventory.manage");
        HotelHousekeepingPo task = requireTask(id);
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new ApiException(409, "HOUSEKEEPING_STATE_INVALID", "清扫任务不是进行中状态");
        }
        TenantContext context = requireStore();
        if (roomMapper.markClean(task.getRoomId(), context.tenantId(), context.storeId()) == 0) {
            throw new ApiException(409, "ROOM_STATE_INVALID", "房间不是待清扫状态");
        }
        task.setStatus("DONE");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
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
