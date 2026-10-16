package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code InventoryTransactionPo}（JSON 字段名一个不改）</b>：
 * {@code GET /admin/inventory/transactions}、{@code POST /admin/inventory/receipts}、
 * {@code POST /admin/inventory/adjustments} 的响应体早已是对外契约。其中 V24 新增的三列
 * （{@link #unitCost} / {@link #totalCost} / {@link #currencyCode}）在原 PO 上就是字段，
 * 这里一个不少地保留。
 *
 * <p><b>{@code createdAt} 是业务时间列</b>（本表没有独立的 {@code occurred_at} 列，DDL 注释即「发生时间」）：
 * 它在领域模型上（见 {@code InventoryTransaction}），是列表的排序与区间筛选依据，因此读路径也是真实值。
 *
 * <p><b>{@code createdBy} 是记账列</b>，领域模型不承载（standards/10），因此本视图恒为 {@code null}：
 * 迁移前也只有列表读路径能从库里读到 0，而 {@code changeStock} 的响应本来就是 null
 * （MyBatis-Plus 的 {@code insert} 不回读数据库默认值）。字段名保留即契约不变。
 *
 * @param id              流水标识
 * @param tenantId        租户
 * @param storeId         门店
 * @param materialId      物料
 * @param transactionType RECEIPT / CONSUME / ADJUST_IN / ADJUST_OUT / REVERSE
 * @param quantityDelta   库存变化量（带符号）
 * @param quantityBefore  变化前现有量
 * @param quantityAfter   变化后现有量
 * @param unitCost        单价（入库 = 批次单价，出库 = 结转的平均成本）；历史行为 null
 * @param totalCost       成本发生额（带符号）；历史行为 null
 * @param currencyCode    单价/发生额币种快照
 * @param sourceType      来源类型
 * @param sourceId        来源业务 ID
 * @param idempotencyKey  幂等键
 * @param reason          原因
 * @param createdBy       非领域字段：记账列，恒为 null（见类注释）
 * @param createdAt       发生时间
 */
public record InventoryTransactionView(
    Long id,
    Long tenantId,
    Long storeId,
    Long materialId,
    String transactionType,
    BigDecimal quantityDelta,
    BigDecimal quantityBefore,
    BigDecimal quantityAfter,
    BigDecimal unitCost,
    BigDecimal totalCost,
    String currencyCode,
    String sourceType,
    String sourceId,
    String idempotencyKey,
    String reason,
    Long createdBy,
    LocalDateTime createdAt) {

  /** 由领域模型装配视图；{@code null} 返回 {@code null}。 */
  public static InventoryTransactionView from(InventoryTransaction transaction) {
    if (transaction == null) {
      return null;
    }
    return new InventoryTransactionView(transaction.id(), transaction.tenantId(), transaction.storeId(),
        transaction.materialId(), transaction.transactionType(), transaction.quantityDelta(),
        transaction.quantityBefore(), transaction.quantityAfter(), transaction.unitCost(), transaction.totalCost(),
        transaction.currencyCode(), transaction.sourceType(), transaction.sourceId(), transaction.idempotencyKey(),
        transaction.reason(), null, transaction.createdAt());
  }
}
