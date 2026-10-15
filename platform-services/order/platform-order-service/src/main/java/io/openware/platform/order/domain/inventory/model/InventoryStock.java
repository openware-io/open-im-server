package io.openware.platform.order.domain.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存余额（领域模型）。
 *
 * <p>此前应用层直接用 MyBatis 持久化对象 {@code InventoryStockPo} 承载该概念，使库存这一业务对象与
 * 表映射、MyBatis 注解、以及 {@code LambdaQueryWrapper} 查询构造耦合在一起。本类型只表达业务事实：
 * 某（租户, 门店, 物料）下的现有量、预留量、移动加权平均成本及其币种快照。
 *
 * <p>不可变：库存变更走仓储端口上的**原子方法**（见 {@code InventoryStockRepository}），
 * 而不是在此对象上 setter 改字段——并发扣减的正确性依赖数据库条件更新，不依赖内存中的读改写。
 *
 * @param id           库存行主键
 * @param tenantId     租户
 * @param storeId      门店
 * @param materialId   物料
 * @param onHandQty    现有量
 * @param reservedQty  预留量
 * @param avgCost      移动加权平均成本（最小货币单位/计量单位，V24）；{@code 0} 表示尚未建立成本基准
 * @param currencyCode {@code avgCost} 的币种快照；币种变更绝不与历史成本混合累加
 * @param version      乐观锁版本号
 * @param createdBy    创建人
 * @param createdAt    创建时间
 * @param updatedBy    更新人
 * @param updatedAt    更新时间
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
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /** 可动用量 = 现有量 − 预留量。扣减的准入判定与此口径一致（见仓储端口的扣减约定）。 */
  public BigDecimal availableQty() {
    BigDecimal onHand = onHandQty == null ? BigDecimal.ZERO : onHandQty;
    BigDecimal reserved = reservedQty == null ? BigDecimal.ZERO : reservedQty;
    return onHand.subtract(reserved);
  }
}
