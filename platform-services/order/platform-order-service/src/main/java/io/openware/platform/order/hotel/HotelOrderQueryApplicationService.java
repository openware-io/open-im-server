package io.openware.platform.order.hotel;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.util.List;
import org.springframework.stereotype.Service;

/** 酒店订单读模型：预订与交易订单关联后的查询视图。 */
@Service
public class HotelOrderQueryApplicationService {

    private final HotelOrderQueryMapper orderQueryMapper;

    public HotelOrderQueryApplicationService(HotelOrderQueryMapper orderQueryMapper) {
        this.orderQueryMapper = orderQueryMapper;
    }

    /** 酒店订单列表：订单号/预订号/住客/房型/房号/入离/状态/金额。 */
    public List<HotelOrderView> orders(String bookingStatus) {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        List<HotelOrderView> rows = orderQueryMapper.selectHotelOrders(context.tenantId(), context.storeId());
        maskGuestPii(rows, context);
        if (bookingStatus == null || bookingStatus.isBlank()) {
            return rows;
        }
        return rows.stream().filter(row -> bookingStatus.equals(row.getBookingStatus())).toList();
    }

    private static void maskGuestPii(List<HotelOrderView> rows, TenantContext context) {
        boolean canViewPii = context.permissions() != null
                && context.permissions().contains("member.pii.view");
        if (canViewPii || rows == null) {
            return;
        }
        for (HotelOrderView row : rows) {
            row.setGuestName(maskName(row.getGuestName()));
        }
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "酒店订单必须在门店上下文查询");
        }
        return context;
    }
}
