package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 酒店主数据：酒店/房型/房间/房价计划/库存日历。全部按门店上下文隔离。 */
@Service
public class HotelMasterDataApplicationService {

    private final HotelMapper hotelMapper;
    private final HotelRoomTypeMapper roomTypeMapper;
    private final HotelRoomMapper roomMapper;
    private final HotelRatePlanMapper ratePlanMapper;
    private final HotelInventoryMapper inventoryMapper;

    public HotelMasterDataApplicationService(HotelMapper hotelMapper, HotelRoomTypeMapper roomTypeMapper,
                                             HotelRoomMapper roomMapper, HotelRatePlanMapper ratePlanMapper,
                                             HotelInventoryMapper inventoryMapper) {
        this.hotelMapper = hotelMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.roomMapper = roomMapper;
        this.ratePlanMapper = ratePlanMapper;
        this.inventoryMapper = inventoryMapper;
    }

    public List<HotelPo> hotels() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        return hotelMapper.selectList(new LambdaQueryWrapper<HotelPo>()
                .eq(HotelPo::getStoreId, context.storeId())
                .orderByAsc(HotelPo::getId));
    }

    @Transactional
    public HotelPo saveHotel(Long id, String name, String address, String timezone,
                             String checkInTime, String checkOutTime) {
        PermissionGuard.require("hotel.inventory.manage");
        TenantContext context = requireStore();
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "HOTEL_NAME_REQUIRED", "酒店名称不能为空");
        }
        HotelPo po = id == null ? new HotelPo() : requireHotel(id, context);
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setName(name.trim());
        po.setAddress(address);
        po.setTimezone(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        po.setCheckInTime(checkInTime == null ? "14:00" : checkInTime);
        po.setCheckOutTime(checkOutTime == null ? "12:00" : checkOutTime);
        po.setStatus("ACTIVE");
        return persist(po);
    }

    public List<HotelRoomTypePo> roomTypes(Long hotelId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        LambdaQueryWrapper<HotelRoomTypePo> query = new LambdaQueryWrapper<HotelRoomTypePo>()
                .eq(HotelRoomTypePo::getStoreId, context.storeId())
                .orderByAsc(HotelRoomTypePo::getId);
        if (hotelId != null) query.eq(HotelRoomTypePo::getHotelId, hotelId);
        return roomTypeMapper.selectList(query);
    }

    @Transactional
    public HotelRoomTypePo saveRoomType(Long id, Long hotelId, String code, String name, Integer capacity,
                                        String bedType, BigDecimal areaSqm) {
        PermissionGuard.require("hotel.inventory.manage");
        TenantContext context = requireStore();
        if (hotelId == null || code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new ApiException(400, "ROOM_TYPE_FIELDS_REQUIRED", "酒店、房型编码和名称不能为空");
        }
        requireHotel(hotelId, context);
        HotelRoomTypePo po = id == null ? new HotelRoomTypePo() : requireRoomType(id, context);
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setHotelId(hotelId);
        po.setCode(code.trim());
        po.setName(name.trim());
        po.setCapacity(capacity == null || capacity < 1 ? 1 : capacity);
        po.setBedType(bedType);
        po.setAreaSqm(areaSqm);
        po.setStatus("ACTIVE");
        return persist(po);
    }

    public List<HotelRoomPo> rooms(Long roomTypeId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        LambdaQueryWrapper<HotelRoomPo> query = new LambdaQueryWrapper<HotelRoomPo>()
                .eq(HotelRoomPo::getStoreId, context.storeId())
                .orderByAsc(HotelRoomPo::getRoomNo);
        if (roomTypeId != null) query.eq(HotelRoomPo::getRoomTypeId, roomTypeId);
        return roomMapper.selectList(query);
    }

    @Transactional
    public HotelRoomPo saveRoom(Long id, Long roomTypeId, String roomNo, String floor) {
        PermissionGuard.require("hotel.inventory.manage");
        TenantContext context = requireStore();
        if (roomTypeId == null || roomNo == null || roomNo.isBlank()) {
            throw new ApiException(400, "ROOM_FIELDS_REQUIRED", "房型和房号不能为空");
        }
        HotelRoomTypePo roomType = requireRoomType(roomTypeId, context);
        HotelRoomPo po = id == null ? new HotelRoomPo() : requireRoom(id, context);
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setHotelId(roomType.getHotelId());
        po.setRoomTypeId(roomTypeId);
        po.setRoomNo(roomNo.trim());
        po.setFloor(floor);
        po.setStatus("ACTIVE");
        return persist(po);
    }

    public List<HotelRatePlanPo> ratePlans(Long hotelId) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        LambdaQueryWrapper<HotelRatePlanPo> query = new LambdaQueryWrapper<HotelRatePlanPo>()
                .eq(HotelRatePlanPo::getStoreId, context.storeId())
                .orderByAsc(HotelRatePlanPo::getId);
        if (hotelId != null) query.eq(HotelRatePlanPo::getHotelId, hotelId);
        return ratePlanMapper.selectList(query);
    }

    @Transactional
    public HotelRatePlanPo saveRatePlan(Long id, Long hotelId, String code, String name,
                                        Long basePriceMinor, String currency) {
        PermissionGuard.require("hotel.rate.update");
        TenantContext context = requireStore();
        if (hotelId == null || code == null || code.isBlank() || name == null || name.isBlank()
                || basePriceMinor == null || basePriceMinor < 0) {
            throw new ApiException(400, "RATE_PLAN_FIELDS_REQUIRED", "酒店、编码、名称和价格不能为空");
        }
        requireHotel(hotelId, context);
        HotelRatePlanPo po = id == null ? new HotelRatePlanPo() : requireRatePlan(id, context);
        po.setTenantId(context.tenantId());
        po.setStoreId(context.storeId());
        po.setHotelId(hotelId);
        po.setCode(code.trim());
        po.setName(name.trim());
        po.setBasePriceMinor(basePriceMinor);
        po.setCurrency(currency == null || currency.isBlank() ? "USD" : currency.toUpperCase());
        po.setStatus("ACTIVE");
        return persist(po);
    }

    public List<HotelInventoryPo> inventory(Long roomTypeId, LocalDate from, LocalDate to) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        if (roomTypeId == null || from == null || to == null || !to.isAfter(from)) {
            throw new ApiException(400, "INVENTORY_RANGE_INVALID", "房型和日期范围不能为空");
        }
        requireRoomType(roomTypeId, context);
        return inventoryMapper.selectList(new LambdaQueryWrapper<HotelInventoryPo>()
                .eq(HotelInventoryPo::getStoreId, context.storeId())
                .eq(HotelInventoryPo::getRoomTypeId, roomTypeId)
                .ge(HotelInventoryPo::getStayDate, from)
                .lt(HotelInventoryPo::getStayDate, to)
                .orderByAsc(HotelInventoryPo::getStayDate));
    }

    @Transactional
    public HotelInventoryPo setInventory(Long roomTypeId, LocalDate stayDate, Integer total) {
        PermissionGuard.require("hotel.inventory.manage");
        TenantContext context = requireStore();
        if (roomTypeId == null || stayDate == null || total == null || total < 0) {
            throw new ApiException(400, "INVENTORY_FIELDS_INVALID", "房型、日期和库存不能为空");
        }
        requireRoomType(roomTypeId, context);
        HotelInventoryPo po = inventoryMapper.selectOne(new LambdaQueryWrapper<HotelInventoryPo>()
                .eq(HotelInventoryPo::getStoreId, context.storeId())
                .eq(HotelInventoryPo::getRoomTypeId, roomTypeId)
                .eq(HotelInventoryPo::getStayDate, stayDate));
        if (po == null) {
            po = new HotelInventoryPo();
            po.setTenantId(context.tenantId());
            po.setStoreId(context.storeId());
            po.setRoomTypeId(roomTypeId);
            po.setStayDate(stayDate);
            po.setSold(0);
            po.setHeld(0);
            po.setTotal(total);
            po.setCreatedAt(LocalDateTime.now());
            po.setUpdatedAt(LocalDateTime.now());
            po.setVersion(0);
            inventoryMapper.insert(po);
            return po;
        }
        if (total < po.getSold() + po.getHeld()) {
            throw new ApiException(409, "INVENTORY_BELOW_CONSUMED", "库存不能小于已售和已占用数量");
        }
        po.setTotal(total);
        po.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(po);
        return po;
    }

    private HotelPo persist(HotelPo po) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (po.getId() == null) {
                po.setCreatedAt(now);
                po.setUpdatedAt(now);
                po.setVersion(0);
                hotelMapper.insert(po);
            } else {
                po.setUpdatedAt(now);
                hotelMapper.updateById(po);
            }
            return po;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(409, "HOTEL_DUPLICATE", "酒店资料已存在");
        }
    }

    private HotelRoomTypePo persist(HotelRoomTypePo po) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (po.getId() == null) {
                po.setCreatedAt(now);
                po.setUpdatedAt(now);
                po.setVersion(0);
                roomTypeMapper.insert(po);
            } else {
                po.setUpdatedAt(now);
                roomTypeMapper.updateById(po);
            }
            return po;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(409, "ROOM_TYPE_DUPLICATE", "房型编码已存在");
        }
    }

    private HotelRoomPo persist(HotelRoomPo po) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (po.getId() == null) {
                po.setCreatedAt(now);
                po.setUpdatedAt(now);
                po.setVersion(0);
                roomMapper.insert(po);
            } else {
                po.setUpdatedAt(now);
                roomMapper.updateById(po);
            }
            return po;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(409, "ROOM_DUPLICATE", "房号已存在");
        }
    }

    private HotelRatePlanPo persist(HotelRatePlanPo po) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (po.getId() == null) {
                po.setCreatedAt(now);
                po.setUpdatedAt(now);
                po.setVersion(0);
                ratePlanMapper.insert(po);
            } else {
                po.setUpdatedAt(now);
                ratePlanMapper.updateById(po);
            }
            return po;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(409, "RATE_PLAN_DUPLICATE", "房价计划编码已存在");
        }
    }

    private HotelPo requireHotel(Long id, TenantContext context) {
        HotelPo po = hotelMapper.selectById(id);
        if (po == null || !context.storeId().equals(po.getStoreId())) {
            throw new ApiException(404, "HOTEL_NOT_FOUND", "酒店不存在");
        }
        return po;
    }

    private HotelRoomTypePo requireRoomType(Long id, TenantContext context) {
        HotelRoomTypePo po = roomTypeMapper.selectById(id);
        if (po == null || !context.storeId().equals(po.getStoreId())) {
            throw new ApiException(404, "ROOM_TYPE_NOT_FOUND", "房型不存在");
        }
        return po;
    }

    private HotelRoomPo requireRoom(Long id, TenantContext context) {
        HotelRoomPo po = roomMapper.selectById(id);
        if (po == null || !context.storeId().equals(po.getStoreId())) {
            throw new ApiException(404, "ROOM_NOT_FOUND", "房间不存在");
        }
        return po;
    }

    private HotelRatePlanPo requireRatePlan(Long id, TenantContext context) {
        HotelRatePlanPo po = ratePlanMapper.selectById(id);
        if (po == null || !context.storeId().equals(po.getStoreId())) {
            throw new ApiException(404, "RATE_PLAN_NOT_FOUND", "房价计划不存在");
        }
        return po;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "酒店主数据必须在门店上下文操作");
        }
        return context;
    }
}
