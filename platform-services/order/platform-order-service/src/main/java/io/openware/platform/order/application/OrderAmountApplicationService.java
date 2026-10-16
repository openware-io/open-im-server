package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 订单金额快照维护：按「已生效（ACTIVE）加项」重算订单的
 * subtotal / discount / tax / totalAmount，并把 refundableAmount 对齐为已收金额。
 *
 * <p>口径与对客账单（{@link BillApplicationService}）、结算（{@link SettlementApplicationService}）一致：
 * 只统计 ACTIVE 明细，待确认（PENDING_APPROVAL）与已拒绝（REJECTED）不计入。
 *
 * <p>为什么必须调用：加项/确认加项/结台只写明细、不更新订单金额时，未结算订单的
 * {@code ord_order.total_amount} 会一直是 0 —— 订单列表（B 端卡片）、收银应付校验
 * （{@code PAYMENT_AMOUNT_MISMATCH}）与账单三处口径不一致，表现为「开台加项后金额不对」。
 *
 * <p>只改金额字段。**版本参与判定并自增**（见下），状态原样写回当前值。
 *
 * <p>落库走带版本条件的乐观锁提交：本方法有直连 {@code /business/**} 的调用方
 * （{@code OrderItemController} 加项后重算），没有上层守卫；
 * 两个并发加项/重算都读到同一 version 时，无条件写回会让后写者覆盖前者算出的应付金额。
 *
 * <p><b>领域模型不可变</b>：{@link #recalculate(Order)} 返回**新实例**，
 * 调用方必须用返回值继续（`order = orderAmounts.recalculate(order);`）——原地改字段的写法已随之消失。
 */
@Service
public class OrderAmountApplicationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderAmountApplicationService(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    /** 按订单 id 重算金额并落库；订单不存在时返回 null（调用方已做存在性校验）。 */
    @Transactional
    public Order recalculate(Long orderId) {
        Order order = orderId == null ? null : orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        Order recalculated = recalculate(order);
        // 条件更新：只有库中版本仍是刚读到的那个才写入。受影响 0 行 = 期间已被他人重算，
        // 必须报冲突而不是静默覆盖对方算出的金额。
        int expectedVersion = order.currentVersion();
        if (!orderRepository.updateAmountsAndStatusWithVersion(recalculated, expectedVersion)) {
            throw new BusinessException("ORDER_VERSION_CONFLICT", "订单金额已被并发修改，请重试");
        }
        return recalculated.withVersion(expectedVersion + 1);
    }

    /**
     * 在给定订单对象上重算金额字段（不落库），**返回新实例**，供需要「一次写库」的调用方复用：
     * 例如结台时同时改状态与金额，必须先用返回值再写库，避免被旧实例覆盖。
     */
    public Order recalculate(Order order) {
        BigDecimal subtotal = ZERO;
        BigDecimal discount = ZERO;
        BigDecimal tax = ZERO;
        for (OrderItem item : activeItems(order.id())) {
            // 以明细的 total_amount 为准（房费含超时加价、服务人员费含递增舍入，不能用 单价×数量 反推，
            // 否则订单合计会与账单明细之和对不上）；历史数据 total_amount 为空时退回 单价×数量。
            subtotal = subtotal.add(lineAmount(item));
            discount = discount.add(item.discountAmount() == null ? ZERO : item.discountAmount());
            tax = tax.add(item.taxAmount() == null ? ZERO : item.taxAmount());
        }
        BigDecimal total = subtotal.subtract(discount).add(tax);
        BigDecimal paid = order.paid();
        // 金额列均为 decimal(20,6)：内存值按同精度归一，避免响应里出现 14800.000000000000 这类 12 位小数。
        // 可退金额 = 已收（暂无退款回冲流程；收款时由 OrderBillingMapper.markPaid 同步）。
        // 不能写成「合计 − 已收」：未收款的订单会变成「可退一整单」。
        return order.withAmounts(scale6(subtotal), scale6(discount), scale6(tax), scale6(total), scale6(paid));
    }

    /** 明细行金额：优先 total_amount，为空时退回 unit_price × quantity。 */
    private static BigDecimal lineAmount(OrderItem item) {
        if (item.totalAmount() != null) {
            return item.totalAmount();
        }
        BigDecimal unitPrice = item.unitPrice() == null ? ZERO : item.unitPrice();
        BigDecimal quantity = item.quantity() == null ? ZERO : item.quantity();
        return unitPrice.multiply(quantity);
    }

    /**
     * 订单合计基数（**唯一口径**）：库内 {@code total_amount} 为正时就是它，否则按「已生效明细合计 − 优惠 + 税」
     * 回退并按 0 收敛。
     *
     * <p>为什么要有回退：存量订单/异常写入可能停在 0（或负），此时账单不能显示「应收 0」；
     * 但**回退必须三处同一份**——账单（{@code BillApplicationService#resolveTotal}）与订单投影
     * （{@code OrderController#liveTotal}）此前各写一套，库内合计为 0 时看板显示 0、账单显示明细之和。
     * 现在两边都调用本方法，输入同一个「已生效明细合计」。
     *
     * @param order            订单（读 total_amount / discount_amount / tax_amount）
     * @param activeItemsMinor 该订单**全部** ACTIVE 明细的合计（最小货币单位整数）
     */
    public static long totalBasisMinor(Order order, long activeItemsMinor) {
        long stored = minor(order == null ? null : order.totalAmount());
        if (stored > 0) {
            return stored;
        }
        long discount = minor(order == null ? null : order.discountAmount());
        long tax = minor(order == null ? null : order.taxAmount());
        return Math.max(0L, activeItemsMinor - discount + tax);
    }

    /**
     * 差额法（**唯一实现**）：把合计里的「库内房费明细快照」换成「展示口径的房费」，结果不为负。
     *
     * <p>开台中：库内合计里含上次刷新写进的 ROOM_FEE 快照，而展示要的是此刻结台的实时房费，
     * 直接相加会把包厢费算两遍（线上实测：账单 6150、卡片 10150）。差额法同时保留折扣/税的既有口径。
     * 账单与订单投影共用本方法，不允许任何一处再写一遍加减。
     *
     * @param basisMinor            订单合计基数（见 {@link #totalBasisMinor(Order, long)}）
     * @param storedRoomFeeMinor    库内 ROOM_FEE 明细合计（要被换掉的快照）
     * @param shownRoomFeeMinor     展示口径的房费（实时值或固化值）
     */
    public static long withRoomFeeReplaced(long basisMinor, long storedRoomFeeMinor, long shownRoomFeeMinor) {
        return Math.max(0L, basisMinor - storedRoomFeeMinor + shownRoomFeeMinor);
    }

    /** 订单域金额（decimal(20,6) 列，存的就是最小货币单位整数）→ long：只做类型收敛，不做元/分换算。 */
    private static long minor(BigDecimal amount) {
        return amount == null ? 0L : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal scale6(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    /** 已生效加项（与账单/结算同一口径：仅 status=ACTIVE）。 */
    public List<OrderItem> activeItems(Long orderId) {
        return orderItemRepository.list(OrderItemQuery.activeOfOrder(orderId)).stream()
                .map(row -> row.aggregate())
                .toList();
    }

    /**
     * 是否存在待确认的客户自助加项（{@code PENDING_APPROVAL}）。
     *
     * <p>用途：结台自动结算前的前置判断。这类加项**不计入应付**（与账单/结算同一口径），
     * 若在它们未处理时就把订单推进到「待收款」，顾客点的东西会从账单里消失（漏收）；
     * 因此有它们时结台不自动结算，由门店先确认/拒绝。
     */
    public boolean hasPendingApprovalItems(Long orderId) {
        return orderItemRepository.existsPendingApproval(orderId);
    }
}
