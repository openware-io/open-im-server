package io.openware.platform.order.domain.inventory.repository;

import io.openware.platform.order.domain.inventory.model.InventoryScope;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库存余额仓储端口。
 *
 * <p>本端口刻意**不暴露** {@code LambdaQueryWrapper}、{@code BaseMapper} 或任何持久化类型：查询条件以
 * 业务语义命名，使应用层只表达"要什么"，不表达"怎么查"。
 *
 * <p><b>作用域（V24/V25）</b>：余额的作用域是 {@code tenant_id + business_type + store_id +
 * warehouse_id + material_id}（见 {@link InventoryScope}）；{@code organization_id} 只是归属/报表快照，
 * 不进入唯一键，但写路径会用上下文里的组织快照校验并落库。所有按余额定位的方法都收口为
 * {@link InventoryScope} 参数，避免调用方漏掉业态/仓库维度。
 *
 * <p><b>原子性契约（实现必须遵守，不得退化为"先读后判"）</b>
 * <ul>
 *   <li>{@link #deductAvailable} 必须是**单条带上下界的条件更新**：仅当
 *       {@code on_hand_qty - reserved_qty >= quantity} 时才扣减，并以受影响行数表达结果。
 *       防超卖依赖数据库在单条语句内以行锁判定；若实现改成"先 {@code findForUpdate} 再判断再写回"，
 *       并发下可能出现负库存。</li>
 *   <li>{@link #increaseOnHand} 必须在**同一条语句内**同时落库新的移动加权平均成本，
 *       调用方须先持有 {@link #findForUpdate} 的行锁，使并发入库对同一作用域串行，
 *       避免"两批都基于旧平均成本重算"的丢失更新。</li>
 * </ul>
 */
public interface InventoryStockRepository {

  /** 加行锁读取库存行（事务内持有至提交）；不存在时返回空。作用域必须带仓库。 */
  Optional<InventoryStock> findForUpdate(InventoryScope scope, Long materialId);

  /** 不加锁读取单条库存余额；不存在时返回空。用于只读查询。作用域必须带仓库。 */
  Optional<InventoryStock> find(InventoryScope scope, Long materialId);

  /**
   * 按门店（+业态，仓库为 {@code null} 时不限仓库）批量读取余额，便于一次查询内做映射。
   * 物料列表的结存展示与点单可用量都走这里。
   */
  List<InventoryStock> findByMaterials(InventoryScope storeScope, List<Long> materialIds);

  /** 按门店（+业态）读取全部库存余额（库存成本查询口径；不指定仓库时覆盖该门店所有仓）。 */
  List<InventoryStock> findByStore(InventoryScope storeScope);

  /**
   * 原子扣减可用库存，返回是否扣减成功。
   *
   * <p>返回 {@code false} 表示可用库存不足（对应数据库 0 行受影响），调用方据此抛
   * {@code INVENTORY_INSUFFICIENT}；不得用异常表达"库存不足"这一业务结果。
   */
  boolean deductAvailable(InventoryScope scope, Long materialId, BigDecimal quantity, LocalDateTime updatedAt);

  /**
   * 入库/调整入库/调拨入库/作废回补：原子增加现有库存并落库新的移动加权平均成本。
   *
   * @param avgCost      本次入库后的移动加权平均成本
   * @param currencyCode 该平均成本的币种快照
   * @return 受影响行数（0 表示目标库存行不存在）
   */
  int increaseOnHand(InventoryScope scope, Long materialId, BigDecimal quantity, BigDecimal avgCost,
      String currencyCode, LocalDateTime updatedAt);

  /**
   * 新建库存行（首次入库时建立余额记录，见 {@link InventoryStock#empty}）。
   *
   * <p><b>本端口没有「整行写回」的更新方法，这是结构性的</b>：库存的每一次变更都必须走
   * {@link #deductAvailable} / {@link #increaseOnHand} 的原子条件语句。一旦提供「按 id 覆盖写余额行」
   * 的方法，调用方就可能在应用层拼出「先读后判再写回」的路径，把防超卖重新变成竞态，
   * 而领域模型是不可变记录、没有任何 setter，正是为了让这条路径无从表达。
   *
   * <p>并发建行由唯一键
   * {@code uk_ord_inventory_stock_scope(tenant_id, business_type, store_id, warehouse_id, material_id)} 兜底：
   * 抢输的事务会收到 {@code DuplicateKeyException}，由调用方忽略冲突后重新加锁读取。
   */
  InventoryStock save(InventoryStock stock);
}
