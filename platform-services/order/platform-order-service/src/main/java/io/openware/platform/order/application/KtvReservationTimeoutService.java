package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.ReservationMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 预约超时（未到店）自动处理：**到店时间 + 宽限期**仍未到店 → 释放包厢 / 标记未到店。
 *
 * <p>解决的问题（门店实测）：预约放了鸽子后，订单一直停在「已预留」、包厢永远显示「已预订」，
 * 房态永不释放；旧实现里 {@code NO_SHOW} 只能人工点，且 {@code noShow()} 明确「不释放占用、不碰订单」。
 *
 * <p>两条口径：
 * <ol>
 *   <li><b>未开台的预留会话</b>（{@code status=RESERVED} 且 {@code opened_at IS NULL}，参考时间取
 *       预约到店时间、没有则退回创建时间）：订单还活着 → 走 {@link OrderCancellationApplicationService#cancel}
 *       取消订单（**它内部级联** {@code KtvSessionApplicationService#cancelByOrder}，释放资源域占用）；
 *       订单已终结（历史孤儿：作废/取消但会话没收敛）→ 只收敛会话，不留占房。</li>
 *   <li><b>未转化的预约</b>（{@code PENDING/CONFIRMED} 且已过到店时间）→ {@code NO_SHOW} 未到店 + 审计留痕。
 *       （预约本身不占资源，见 {@code ReservationApplicationService#assignRoom}；这条是补状态与留痕。）</li>
 * </ol>
 *
 * <p><b>有收款的订单不动</b>：订单取消自带 {@code ORDER_HAS_PAYMENT_REFUND_FIRST} 守卫（已收款要先退款），
 * 这里捕获该错误计入 {@code needsManual}，留给人工处理，不擅自释放。
 *
 * <p>逐会话/逐预约 try-catch + 逐行设置租户上下文（与 {@code KtvSessionApplicationService#refreshOpenRoomFees}
 * 同范式）：单条失败不影响整批，下一轮重试。
 */
@Slf4j
@Service
public class KtvReservationTimeoutService {

    /** 自动释放的取消原因：写进订单审计，运营在操作日志里能看懂是谁、为什么释放的。 */
    public static final String AUTO_RELEASE_REASON = "预约超时未到店，系统自动释放包厢";

    private static final Set<String> TERMINAL_ORDER_STATUSES =
            Set.of("COMPLETED", "CANCELLED", "VOIDED", "REFUNDED", "PARTIAL_REFUNDED");

    private final KtvSessionMapper sessionMapper;
    private final ReservationMapper reservationMapper;
    private final OrderMapper orderMapper;
    private final KtvSessionApplicationService ktvSessionService;
    private final OrderCancellationApplicationService orderCancellationService;
    private final ReservationApplicationService reservationService;

    public KtvReservationTimeoutService(KtvSessionMapper sessionMapper,
                                        ReservationMapper reservationMapper,
                                        OrderMapper orderMapper,
                                        KtvSessionApplicationService ktvSessionService,
                                        OrderCancellationApplicationService orderCancellationService,
                                        ReservationApplicationService reservationService) {
        this.sessionMapper = sessionMapper;
        this.reservationMapper = reservationMapper;
        this.orderMapper = orderMapper;
        this.ktvSessionService = ktvSessionService;
        this.orderCancellationService = orderCancellationService;
        this.reservationService = reservationService;
    }

    /** 一轮巡检的统计（用于日志与测试断言）。 */
    public record SweepResult(int releasedOrders, int orphanSessions, int needsManual, int noShowReservations) {
        public int touched() {
            return releasedOrders + orphanSessions + noShowReservations;
        }
    }

    /**
     * 执行一轮超时巡检。
     *
     * @param graceMinutes 宽限期（分钟）：到店时间/创建时间 + 宽限期之后仍未到店才处理；小于 1 按 1 处理
     */
    public SweepResult sweep(int graceMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, graceMinutes));

        int releasedOrders = 0;
        int orphanSessions = 0;
        int needsManual = 0;
        for (KtvSessionPo session : safe(sessionMapper.selectReservedOverdue(cutoff))) {
            TenantContext previous = TenantContextHolder.get();
            try {
                TenantContextHolder.set(new TenantContext(session.getTenantId(), null, null, 0L, 0));
                OrderPo order = orderMapper.selectById(session.getOrderId());
                if (order == null || TERMINAL_ORDER_STATUSES.contains(order.getStatus())) {
                    // 孤儿会话：订单已终结（作废/取消/已完成）但会话还停在「已预留」——只收敛会话，释放包厢。
                    ktvSessionService.cancelByOrder(session.getOrderId());
                    orphanSessions++;
                    log.info("收敛孤儿预留会话: sessionId={}, orderId={}, orderStatus={}",
                            session.getId(), session.getOrderId(), order == null ? "MISSING" : order.getStatus());
                    continue;
                }
                try {
                    // 订单取消内部会级联 cancelByOrder（置 CANCELLED + 释放资源域占用）并写审计。
                    orderCancellationService.cancel(order.getId(), AUTO_RELEASE_REASON);
                    releasedOrders++;
                } catch (ApiException rejected) {
                    if (OrderCancellationApplicationService.PAYMENT_REFUND_FIRST_CODE.equals(rejected.getCode())) {
                        needsManual++;
                        log.warn("预约超时但订单已有收款，需人工处理: orderId={}, reason={}",
                                order.getId(), rejected.getMessage());
                    } else {
                        throw rejected;
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("预约超时释放包厢失败（下一轮重试）: sessionId={}, cause={}",
                        session.getId(), failure.getMessage());
            } finally {
                restoreTenant(previous);
            }
        }

        int noShowReservations = 0;
        for (ReservationPo reservation : safe(reservationMapper.selectNotArrivedOverdue(cutoff))) {
            TenantContext previous = TenantContextHolder.get();
            try {
                TenantContextHolder.set(new TenantContext(reservation.getTenantId(), null, null, 0L, 0));
                // 已转化（有 order_id）的预约由上面的会话分支处理；这里只处理没开台的预约，标「未到店」+审计。
                reservationService.noShow(reservation.getId());
                noShowReservations++;
            } catch (RuntimeException failure) {
                log.warn("预约标记未到店失败（下一轮重试）: reservationId={}, cause={}",
                        reservation.getId(), failure.getMessage());
            } finally {
                restoreTenant(previous);
            }
        }

        return new SweepResult(releasedOrders, orphanSessions, needsManual, noShowReservations);
    }

    private static void restoreTenant(TenantContext previous) {
        if (previous == null) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.set(previous);
        }
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
