package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.util.List;
import org.springframework.stereotype.Service;

/** 酒店前台最小切片：任务列表、在住/账单摘要。入住/退房动作统一走 HotelStayApplicationService。 */
@Service
public class HotelFrontDeskApplicationService {

    private final HotelBookingMapper mapper;
    private final HotelStayMapper stayMapper;
    private final HotelFolioMapper folioMapper;

    public HotelFrontDeskApplicationService(HotelBookingMapper mapper, HotelStayMapper stayMapper,
                                            HotelFolioMapper folioMapper) {
        this.mapper = mapper;
        this.stayMapper = stayMapper;
        this.folioMapper = folioMapper;
    }

    public List<HotelBookingPo> tasks() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStoreContext();
        return mapper.selectList(new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .in(HotelBookingPo::getStatus, List.of("PENDING_CONFIRM", "CONFIRMED", "CHECKED_IN", "DUE_OUT"))
                .orderByAsc(HotelBookingPo::getCheckInDate)
                .orderByAsc(HotelBookingPo::getId));
    }

    /** 是否已有在住记录；历史预订没有 stay，前台不应显示可退房。 */
    public boolean hasStay(Long bookingId) {
        return currentStay(bookingId) != null;
    }

    /** 当前在住记录：房态卡展示实际入住时间、离店时间和入住时长。 */
    public HotelStayPo currentStay(Long bookingId) {
        return stayMapper.selectOne(new LambdaQueryWrapper<HotelStayPo>()
                .eq(HotelStayPo::getBookingId, bookingId)
                .eq(HotelStayPo::getStatus, "IN_HOUSE")
                .orderByDesc(HotelStayPo::getId)
                .last("LIMIT 1"));
    }

    /** 当前账单摘要：房态卡只展示应收/已收，不把账单明细塞进列表接口。 */
    public HotelFolioPo currentFolio(Long bookingId) {
        return folioMapper.selectOne(new LambdaQueryWrapper<HotelFolioPo>()
                .eq(HotelFolioPo::getBookingId, bookingId)
                .eq(HotelFolioPo::getStatus, "OPEN")
                .orderByDesc(HotelFolioPo::getId)
                .last("LIMIT 1"));
    }

    // 入住/退房必须走 HotelStayApplicationService：只改预订状态会绕过房态、在住记录与清扫任务。
    // 这里不再提供直接改预订状态的入口。

    private TenantContext requireStoreContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "前台操作必须在门店上下文中进行");
        }
        return context;
    }
}
