package io.openware.platform.order.domain.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存变动流水（领域模型）：入库/出库/调整/回补每一次库存变更的账目行。
 *
 * <p>此前应用层直接用 MyBatis 持久化对象 {@code InventoryTransactionPo} 承载该概念。本类型只表达业务事实。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_inventory_transaction}）：
 * {@code id / tenant_id / store_id / material_id / transaction_type / quantity_delta / quantity_before /
 * quantity_after / unit_cost / total_cost / currency_code / source_type / source_id / idempotency_key /
 * reason / created_by / created_at / updated_by / updated_at}。持久化对象上没有
 * {@code @TableField(exist = false)} 字段（逐字段核对过），因此本模型与列集合一一对应；
 * 若将来给该 PO 加展示字段，必须同步把它排除在本模型与适配器的列引用之外。
 *
 * <p><b>本表只追加</b>：端口上没有「改一条历史流水」的方法，这是结构性的保证，不是约定。
 *
 * <p><b>审计列</b>：{@code created_by} / {@code updated_by} / {@code updated_at} 不在本模型里
 * （记账信息，由适配器写入与丢弃，见 standards/10）。{@code createdAt} **刻意保留**：
 * 本表没有独立的 {@code occurred_at} 列，它的 DDL 注释就是「发生时间」，既是流水列表的排序/区间过滤依据，
 * 也是响应的 {@code createdAt} 字段——删掉会改成契约（同 {@code PointLedgerEntry.createdAt} 的处理）。
 *
 * <p><b>成本列（V24 移动加权平均）</b>：{@code unitCost} 入库 = 该批次单价、出库 = 当次结转的平均成本；
 * {@code totalCost} 是带符号的发生额（与 {@code quantityDelta} 同号）；{@code currencyCode} 是两者的币种快照。
 * 升级前的历史流水三列为 {@code NULL}（当时没有采集批次单价，不用 0 冒充真实成本）。
 * 三列都是 {@code decimal(20,6)} / {@code varchar(3)}，保持 {@link BigDecimal} 定点表示。
 *
 * @param id              流水标识；{@code null} 表示尚未落库
 * @param tenantId        租户
 * @param storeId         门店
 * @param materialId      物料
 * @param transactionType RECEIPT / CONSUME / ADJUST_IN / ADJUST_OUT / REVERSE
 * @param quantityDelta   库存变化量（带符号：入库正、出库负）
 * @param quantityBefore  变化前现有量
 * @param quantityAfter   变化后现有量
 * @param unitCost        单价（最小货币单位/计量单位）；历史行为 {@code null}
 * @param totalCost       成本发生额（最小货币单位，带符号）；历史行为 {@code null}
 * @param currencyCode    单价/发生额币种快照
 * @param sourceType      来源类型：RECEIPT / ADJUSTMENT / ORDER_ITEM
 * @param sourceId        来源业务 ID
 * @param idempotencyKey  幂等键；租户内唯一（{@code uk_ord_inventory_transaction_idempotency}）
 * @param reason          原因
 * @param createdAt       发生时间（{@code NOT NULL} 真实列，见类注释）
 */
public record InventoryTransaction(
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
    LocalDateTime createdAt) {

  /** 入库（采购收货）。 */
  public static final String TYPE_RECEIPT = "RECEIPT";

  /** 出库（加项消耗）。 */
  public static final String TYPE_CONSUME = "CONSUME";

  /** 调整入库。 */
  public static final String TYPE_ADJUST_IN = "ADJUST_IN";

  /** 调整出库。 */
  public static final String TYPE_ADJUST_OUT = "ADJUST_OUT";

  /** 作废回补（入库方向，按原出库单价还原成本）。 */
  public static final String TYPE_REVERSE = "REVERSE";

  /** 是否入库方向（其余 RECEIPT/ADJUST_IN/REVERSE 之外的类型按出库处理）。 */
  public boolean inbound() {
    return TYPE_RECEIPT.equals(transactionType) || TYPE_ADJUST_IN.equals(transactionType)
        || TYPE_REVERSE.equals(transactionType);
  }

  /**
   * 逐字段核对过：本记录与 {@code ord_inventory_transaction} 的真实列一一对应（无展示字段）。
   *
   * <p>适配器在写入时补齐落库口径的 {@code createdAt}（{@code null} 时取当前时刻），
   * 因此本方法只回答「是否已落库」。
   */
  public boolean persisted() {
    return id != null;
  }
}
