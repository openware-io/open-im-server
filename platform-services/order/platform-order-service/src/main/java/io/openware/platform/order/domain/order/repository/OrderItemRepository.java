package io.openware.platform.order.domain.order.repository;

import io.openware.platform.order.domain.order.model.OrderItem;
import java.util.List;
import java.util.Optional;

/**
 * 订单明细（加项）仓储端口。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_order_item}）：
 * id / tenant_id / store_id / business_type / order_id / item_type / catalog_item_id / resource_id /
 * name_snapshot / unit_price / quantity / discount_amount / tax_amount / total_amount / currency_code /
 * price_snapshot_json / status / source / inventory_status / inventory_material_id /
 * inventory_recovery_decision / inventory_recovery_reason / inventory_recovery_decided_by /
 * inventory_recovery_decided_at + created_by / created_at / updated_by / updated_at。
 * 明细没有 {@code @TableField(exist = false)} 字段，因此领域模型与列一一对应。
 *
 * <p><b>两条批量口径不能合并</b>（调用方不同、语义不同，见
 * {@link #listActiveRoomFeeItemsByOrderIds(List)} 与 {@link #listActiveByOrderIds(List)}）。
 */
public interface OrderItemRepository {

    /** 按标识取明细（租户行拦截器生效：读不到既可能是不存在也可能是跨租户）。 */
    Optional<OrderItem> findById(Long id);

    /**
     * 按条件列出明细，返回「业务聚合 + 记账快照」。
     *
     * <p>为什么带记账快照：待确认加项要展示 {@code created_at}（提交时间，客户已经等了多久），
     * 账单要展示房费明细的 {@code updated_at}（金额的「固化时刻」）；审计列不进领域模型（standards/10），
     * 因此由适配器随行读回。
     */
    List<Persisted<OrderItem>> list(OrderItemQuery query);

    /**
     * 批量取多个订单**已生效（ACTIVE）的包厢计时费明细**：订单列表投影要把库内合计换成实时合计，
     * 必须一次把整页订单的房费明细查出来（逐单调用会变成 N+1）。
     *
     * <p>行口径与账单一致（{@code status=ACTIVE} 的 {@code ROOM_FEE} 行，历史脏数据多行时一并求和）。
     *
     * @param orderIds 订单 id（调用方保证非空：空集合会拼出非法的 {@code IN ()}）
     * @return 命中订单的房费明细行，按 (order_id, id) 升序；没有房费明细的订单不出现在结果里
     */
    List<OrderItem> listActiveRoomFeeItemsByOrderIds(List<Long> orderIds);

    /**
     * 批量取多个订单**全部已生效（ACTIVE）明细**：订单投影的实时合计要用两个数——
     * 全部明细合计（库内合计为 0 时的回退基数）与其中 ROOM_FEE 明细合计（差额法要扣掉的库内房费快照），
     * 一次查询同时拿齐。
     *
     * <p>与 {@link #listActiveRoomFeeItemsByOrderIds(List)} 是**两条不同的口径**（全部 vs 仅房费），
     * 合并会让差额法要么漏扣房费、要么把普通加项当成房费扣掉。
     *
     * @param orderIds 订单 id（调用方保证非空：空集合会拼出非法的 {@code IN ()}）
     * @return 命中订单的全部 ACTIVE 明细行，按 (order_id, id) 升序
     */
    List<OrderItem> listActiveByOrderIds(List<Long> orderIds);

    /**
     * 该订单是否存在**待确认**的客户自助加项（{@code PENDING_APPROVAL}）。
     *
     * <p>用途：结台自动结算前的前置判断。这类加项不计入应付（与账单/结算同一口径），
     * 若在它们未处理时就把订单推进到「待收款」，顾客点的东西会从账单里消失（漏收）。
     * 用存在性判定而不是把计数交给调用方：调用点只关心「有没有」，口径留在适配器里。
     */
    boolean existsPendingApproval(Long orderId);

    /**
     * 新增明细，返回落库后的聚合（含回填主键）。
     *
     * <p>审计列由适配器填充（{@code created_at/updated_at} = 现在）：记账信息不进领域模型。
     */
    OrderItem insert(OrderItem item);

    /**
     * 整实体更新（原 {@code updateById} 的语义：只写实体上非空的列，null 列不参与 SET）。
     *
     * <p>适配器在同一条语句里把 {@code updated_at} 刷成「现在」——它是记账列、不是端口的参数，
     * 但读路径确实会展示它（账单里房费明细的固化时刻）。
     */
    void update(OrderItem item);

    /**
     * 加项审批状态流转的**原子条件更新**：
     * {@code UPDATE ... SET status=?, updated_by=?, updated_at=CURRENT_TIMESTAMP(3)
     * WHERE tenant_id=? AND order_id=? AND id=? AND status='PENDING_APPROVAL'}。
     *
     * <p>为什么必须是条件更新：客户加项后，多个运营（或同一人重复点击/两端同时操作）可能同时点「确认」
     * 或「拒绝」。「先查再写」会双写、甚至出现「先确认又拒绝」的竞态；判定交给数据库后，
     * 受影响 0 行即表示「已被别人处理或状态不符」，调用方据此回 409 或幂等返回。
     *
     * <p>状态口径固定在 SQL 里（{@code status='PENDING_APPROVAL'}）：只有待确认的加项才允许被处理，
     * 调用方无法用它绕过状态机（例如把已 ACTIVE 的明细再改一次）。
     *
     * @param targetStatus 目标状态（{@code ACTIVE} / {@code REJECTED}）
     * @param updatedBy    操作人
     * @return 是否流转成功；{@code false} = 已被并发处理或状态不符
     */
    boolean markApproval(Long tenantId, Long orderId, Long itemId, String targetStatus, Long updatedBy);
}
