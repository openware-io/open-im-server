package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 夜审：按营业日把到离店日期的在住预订转 DUE_OUT，并固化当日汇总。 */
@Service
public class HotelNightAuditApplicationService {

    private final HotelNightAuditMapper auditMapper;
    private final HotelBookingMapper bookingMapper;

    public HotelNightAuditApplicationService(HotelNightAuditMapper auditMapper, HotelBookingMapper bookingMapper) {
        this.auditMapper = auditMapper;
        this.bookingMapper = bookingMapper;
    }

    public List<HotelNightAuditPo> history() {
        PermissionGuard.require("hotel.booking.view");
        TenantContext context = requireStore();
        return auditMapper.selectList(new LambdaQueryWrapper<HotelNightAuditPo>()
                .eq(HotelNightAuditPo::getStoreId, context.storeId())
                .orderByDesc(HotelNightAuditPo::getBusinessDate));
    }

    @Transactional
    public HotelNightAuditPo run(LocalDate businessDate) {
        PermissionGuard.require("hotel.room.manage");
        TenantContext context = requireStore();
        if (businessDate == null) {
            throw new ApiException(400, "BUSINESS_DATE_REQUIRED", "营业日不能为空");
        }
        HotelNightAuditPo existing = auditMapper.selectOne(new LambdaQueryWrapper<HotelNightAuditPo>()
                .eq(HotelNightAuditPo::getStoreId, context.storeId())
                .eq(HotelNightAuditPo::getBusinessDate, businessDate));
        if (existing != null) {
            throw new ApiException(409, "NIGHT_AUDIT_ALREADY_RUN", "该营业日已夜审");
        }
        List<HotelBookingPo> inHouse = bookingMapper.selectList(new LambdaQueryWrapper<HotelBookingPo>()
                .eq(HotelBookingPo::getStoreId, context.storeId())
                .eq(HotelBookingPo::getStatus, "CHECKED_IN"));
        long roomCharge = 0;
        int dueOut = 0;
        LocalDateTime now = LocalDateTime.now();
        for (HotelBookingPo booking : inHouse) {
            roomCharge += booking.getAmountMinor() == null ? 0 : booking.getAmountMinor();
            if (booking.getCheckOutDate() != null && !booking.getCheckOutDate().isAfter(businessDate)) {
                booking.setStatus("DUE_OUT");
                booking.setUpdatedAt(now);
                bookingMapper.updateById(booking);
                dueOut++;
            }
        }
        HotelNightAuditPo audit = new HotelNightAuditPo();
        audit.setTenantId(context.tenantId());
        audit.setStoreId(context.storeId());
        audit.setBusinessDate(businessDate);
        audit.setDueOutCount(dueOut);
        audit.setInHouseCount(inHouse.size());
        audit.setRoomChargeMinor(roomCharge);
        audit.setCurrency(inHouse.isEmpty() ? "USD" : inHouse.get(0).getCurrency());
        audit.setStatus("COMPLETED");
        audit.setCreatedAt(now);
        audit.setUpdatedAt(now);
        auditMapper.insert(audit);
        return audit;
    }

    private TenantContext requireStore() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.storeId() == null) {
            throw new ApiException(403, "STORE_SCOPE_REQUIRED", "夜审必须在门店上下文操作");
        }
        return context;
    }
}
