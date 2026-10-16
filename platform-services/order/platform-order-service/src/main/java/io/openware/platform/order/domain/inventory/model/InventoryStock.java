package io.openware.platform.order.domain.inventory.model;

import java.math.BigDecimal;

/**
 * 库存余额（领域模型）。
 *
 * <p>此前应用层直接用 MyBatis 持久化对象 {@code InventoryStockPo} 承载该概念，使库存这一业务对象与
 * 表映射、MyBatis 注解、以及 {@code LambdaQueryWrapper} 查询构造耦合在一起。本类型只表达业务事实：
 * 某（租户, 门店, 物料）下的现有量、预留量、移动加权平均成本及其币种快照。
 *
 * <p><b>不含审计字段</b>：{@code InventoryStockPo} 上有 {@code created_by / created_at / updated_by /
 * updated_at} 四列，本模型一个都不承载（standards/10「聚合装配与持久化契约」）。迁移时逐处核对过——
 * 应用层没有任何读路径需要它们：列表要的是 {@code on_hand_qty} / {@code reserved_qty}（经
 * {@link #availableQty()} 与余额行本身），成本报表要的是 {@code avg_cost} / {@code currency_code}，
 * 响应视图的记账列由适配器在写入时填充。保留它们只会让「库存余额」这个业务对象顺带承载记账信息。
 *
 * <p><b>{@code avgCost} 是 {@link BigDecimal}</b>：DDL 是 {@code avg_cost decimal(20,6) NOT NULL DEFAULT 0}
 * （最小货币单位/计量单位，V24）。{@code 0} 表示尚未建立成本基准（历史无采购价的物料、或从未入库过）。
 * {@code on_hand_qty} / {@code reserved_qty} 同为 {@code decimal(20,6)}，保持定点表示——
 * 改成整数分会丢掉既有的 6 位小数口径，改成 double 会引入二进制浮点误差。
 *
 * <p>不可变：库存变更走仓储端口上的**原子方法**（见 {@code InventoryStockRepository}），
 * 而不是在此对象上 setter 改字段——并发扣减的正确性依赖数据库条件更新，不依赖内存中的读改写。
 *
 * @param id           库存行主键；{@code null} 表示尚未落库
 * @param tenantId     租户
 * @param storeId      门店
 * @param materialId   物料
 * @param onHandQty    现有量
 * @param reservedQty  预留量
 * @param avgCost      移动加权平均成本（最小货币单位/计量单位，V24）；{@code 0} 表示尚未建立成本基准
 * @param currencyCode {@code avgCost} 的币种快照；币种变更绝不与历史成本混合累加
 * @param version      乐观锁版本号列（并发保护实际由端口上的条件 UPDATE 承担，见端口说明）
 */
public record InventoryStock(
    Long id,
    Long tenantId,
    Long storeId,
    Long materialId,
    BigDecimal onHandQty,
    BigDecimal reservedQty,
    BigDecimal avgCost,
    String currencyCode,
    Integer version) {

  /** 可动用量 = 现有量 − 预留量。扣减的准入判定与此口径一致（见仓储端口的扣减约定）。 */
  public BigDecimal availableQty() {
    BigDecimal onHand = onHandQty == null ? BigDecimal.ZERO : onHandQty;
    BigDecimal reserved = reservedQty == null ? BigDecimal.ZERO : reservedQty;
    return onHand.subtract(reserved);
  }

  /** 现有量（为空视为 0）。 */
  public BigDecimal onHandOrZero() {
    return onHandQty == null ? BigDecimal.ZERO : onHandQty;
  }

  /** 移动加权平均成本（为空视为 0 = 尚未建立成本基准）。 */
  public BigDecimal avgCostOrZero() {
    return avgCost == null ? BigDecimal.ZERO : avgCost;
  }

  /**
   * 首次入库时建立的零额库存行（{@code on_hand_qty = 0}、{@code reserved_qty = 0}、
   * {@code avg_cost = 0}、{@code version = 0}）。
   *
   * <p>{@code currencyCode} 留空：DDL 是 {@code NOT NULL DEFAULT 'USD'}，由库的缺省值填充
   * （与迁移前的 INSERT 口径一致——当时也没有写这一列）。
   */
  public static InventoryStock empty(Long tenantId, Long storeId, Long materialId) {
    return new InventoryStock(null, tenantId, storeId, materialId, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, 0);
  }
}
