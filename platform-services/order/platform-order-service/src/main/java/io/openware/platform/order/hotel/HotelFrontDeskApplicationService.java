package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 酒店前台最小切片：任务列表、入住、退房。动作权限在此强制校验。 */
@Service
public class HotelFrontDeskApplicationService {

    private final HotelBookingMapper mapper;

    public HotelFrontDeskApplicationService(HotelBookingMapper mapper) {
        this.mapper = mapper;
    }

    public List<HotelBookingPo> tasks() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStoreContext();
        return mapper.selectList(new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .in(HotelBookingPo::getStatus, List.of("ARRIVAL", "IN_HOUSE", "DUE_OUT"))
                .orderByAsc(HotelBookingPo::getCheckInDate)
                .orderByAsc(HotelBookingPo::getId));
    }

    @Transactional
    public HotelBookingPo checkIn(Long id) {
        PermissionGuard.require("hotel.checkin");
        HotelBookingPo booking = requireBooking(id);
        if (!"ARRIVAL".equals(booking.getStatus())) {
            throw new ApiException(409, "HOTEL_CHECKIN_STATE_INVALID", "当前预订不在待入住状态");
        }
        booking.setStatus("IN_HOUSE");
        mapper.updateById(booking);
        return booking;
    }

    @Transactional
    public HotelBookingPo checkOut(Long id) {
        PermissionGuard.require("hotel.checkout");
        HotelBookingPo booking = requireBooking(id);
        if (!"IN_HOUSE".equals(booking.getStatus()) && !"DUE_OUT".equals(booking.getStatus())) {
            throw new ApiException(409, "HOTEL_CHECKOUT_STATE_INVALID", "当前预订不在可退房状态");
        }
        booking.setStatus("CHECKED_OUT");
        mapper.updateById(booking);
        return booking;
    }

    private HotelBookingPo requireBooking(Long id) {
        TenantContext context = requireStoreContext();
        HotelBookingPo booking = mapper.selectById(id);
        if (booking == null || !context.storeId().equals(booking.getStoreId())) {
            throw new ApiException(404, "HOTEL_BOOKING_NOT_FOUND", "酒店预订不存在");
        }
        return booking;
    }

    private TenantContext requireStoreContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "前台操作必须在门店上下文中进行");
        }
        return context;
    }
}
