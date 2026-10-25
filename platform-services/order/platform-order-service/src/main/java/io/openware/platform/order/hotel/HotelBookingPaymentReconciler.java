package io.openware.platform.order.hotel;

import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 已支付酒店预订对账：ord_order 已 COMPLETED 时补确认 htl_booking。 */
@Component
@Slf4j
public class HotelBookingPaymentReconciler {

    private final HotelBookingMapper bookingMapper;
    private final HotelInventoryHoldMapper holdMapper;
    private final HotelInventoryMapper inventoryMapper;
    private final HotelBookingNightMapper nightMapper;

    public HotelBookingPaymentReconciler(HotelBookingMapper bookingMapper,
                                          HotelInventoryHoldMapper holdMapper,
                                          HotelInventoryMapper inventoryMapper,
                                          HotelBookingNightMapper nightMapper) {
        this.bookingMapper = bookingMapper;
        this.holdMapper = holdMapper;
        this.inventoryMapper = inventoryMapper;
        this.nightMapper = nightMapper;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 15_000L)
    @Transactional
    public void confirmPaidBookings() {
        List<Long> ids = bookingMapper.selectPaidPendingIds();
        for (Long id : ids) {
            try {
                confirmOne(id);
            } catch (RuntimeException failure) {
                // 单笔失败不阻塞其余订单；保留 PENDING_PAYMENT 供下轮重试/人工处理。
                log.warn("hotel paid booking reconcile failed: bookingId={}, cause={}", id,
                        failure.getMessage());
            }
        }
    }

    private void confirmOne(Long bookingId) {
        HotelBookingPo booking = bookingMapper.selectByIdIgnoreTenant(bookingId);
        if (booking == null || !"PENDING_PAYMENT".equals(booking.getStatus())) {
            return;
        }
        TenantContextHolder.set(new TenantContext(booking.getTenantId(), 0L, booking.getStoreId(),
                0L, 0, List.of(), null, null, null));
        try {
            confirmInTenant(booking);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void confirmInTenant(HotelBookingPo booking) {
        List<HotelInventoryHoldPo> holds = holdMapper.selectList(
                new LambdaQueryWrapper<HotelInventoryHoldPo>()
                        .eq(HotelInventoryHoldPo::getBookingId, booking.getId())
                        .eq(HotelInventoryHoldPo::getStatus, "HELD"));
        for (HotelInventoryHoldPo hold : holds) {
            if (inventoryMapper.confirmHeld(booking.getTenantId(), booking.getStoreId(),
                    hold.getRoomTypeId(), hold.getStayDate(), hold.getRooms()) == 0) {
                throw new IllegalStateException("INVENTORY_CONFIRM_CONFLICT");
            }
            hold.setStatus("SOLD");
            hold.setUpdatedAt(LocalDateTime.now());
            holdMapper.updateById(hold);
        }
        nightMapper.update(null, new LambdaUpdateWrapper<HotelBookingNightPo>()
                .eq(HotelBookingNightPo::getBookingId, booking.getId())
                .eq(HotelBookingNightPo::getStatus, "HELD")
                .set(HotelBookingNightPo::getStatus, "SOLD"));
        booking.setStatus("CONFIRMED");
        booking.setUpdatedAt(LocalDateTime.now());
        bookingMapper.updateById(booking);
        log.info("hotel paid booking confirmed: bookingId={}, orderId={}", booking.getId(),
                booking.getOrderId());
    }
}
