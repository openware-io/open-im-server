package io.openware.platform.order.domain.order.repository;

import io.openware.platform.order.domain.order.model.OrderItem;

/**
 * 订单明细（加项）查询条件。
 *
 * <p>与 {@link OrderQuery} 同款取舍：不暴露 MyBatis 的查询语法，按真实调用场景给具名工厂
 * （每个工厂对应一处调用点，见各方法说明），避免调用方拼出无意义的条件组合。
 *
 * @param tenantId        租户（待确认加项聚合与库存回补列表显式带租户；其余场景交给租户行拦截器）
 * @param orderId         所属订单
 * @param status          明细状态（{@code ACTIVE} / {@code PENDING_APPROVAL} / …）
 * @param itemType        明细类型（{@code ROOM_FEE} / {@code SERVICE} / …）
 * @param inventoryStatus 库存状态（{@code CONSUMED} 用于库存回补列表）
 * @param ordering        行序
 * @param limit           最多返回行数（{@code null} = 不限；房费明细定位与待确认聚合要它）
 */
public record OrderItemQuery(
    Long tenantId,
    Long orderId,
    String status,
    String itemType,
    String inventoryStatus,
    Ordering ordering,
    Integer limit) {

    /** 行序：只提供真实调用点用到的两种。 */
    public enum Ordering {
        /** 按 {@code id} 升序（同一订单内按加项先后；账单/定位/列表都用它）。 */
        ID_ASC,
        /** 先按 {@code created_at} 再按 {@code id} 升序（待确认加项：先提交的先处理）。 */
        CREATED_AT_THEN_ID_ASC
    }

    /** 某订单的全部明细（加项列表；按 id 升序）。 */
    public static OrderItemQuery ofOrder(Long orderId) {
        return new OrderItemQuery(null, orderId, null, null, null, Ordering.ID_ASC, null);
    }

    /**
     * 某订单的**已生效**明细（{@code status=ACTIVE}，按 id 升序）。
     *
     * <p>账单与订单金额重算的唯一口径：待确认（PENDING_APPROVAL）与已拒绝（REJECTED）不计入应付。
     */
    public static OrderItemQuery activeOfOrder(Long orderId) {
        return new OrderItemQuery(null, orderId, OrderItem.STATUS_ACTIVE, null, null, Ordering.ID_ASC, null);
    }

    /**
     * 某租户的待确认加项（{@code status=PENDING_APPROVAL}），按提交时间与 id 升序，最多 {@code limit} 条。
     *
     * <p>租户条件显式落在 {@code tenant_id} 列上（与改造前一致）：待确认聚合是门店维度的提醒，
     * 明细表没有 {@code store_id}，因此必须显式按租户先取行、再回查订单按门店过滤。
     */
    public static OrderItemQuery pendingApprovalOfTenant(Long tenantId, int limit) {
        return new OrderItemQuery(tenantId, null, OrderItem.STATUS_PENDING_APPROVAL, null, null,
            Ordering.CREATED_AT_THEN_ID_ASC, limit);
    }

    /**
     * 某订单内某一类型明细的**第一行**（按 id 升序，取 1 行）。
     *
     * <p>房费明细在订单内唯一（历史脏数据取最早一行，后续刷新都落在它上面），
     * 多行会把包厢费重复计入订单合计。
     */
    public static OrderItemQuery firstOfType(Long orderId, String itemType) {
        return new OrderItemQuery(null, orderId, null, itemType, null, Ordering.ID_ASC, 1);
    }

    /** 某订单**已扣库存**的明细（库存回补列表；按 id 升序）。 */
    public static OrderItemQuery consumedOfOrder(Long tenantId, Long orderId) {
        return new OrderItemQuery(tenantId, orderId, null, null, "CONSUMED", Ordering.ID_ASC, null);
    }
}
