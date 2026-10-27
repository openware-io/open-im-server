package io.openware.platform.order.domain.inventory.repository;

import io.openware.platform.order.domain.inventory.model.InventoryWarehouse;
import java.util.List;
import java.util.Optional;

/**
 * 仓库 / 库位仓储端口（通用库存域，酒店与 KTV 共用）。
 *
 * <p>对应 {@code ord_inventory_warehouse}（V25）。作用域唯一键
 * {@code (tenant_id, business_type, store_id, code)}：同一门店同一业态至少有一个默认仓。
 *
 * <p><b>为什么端口上有「懒创建默认仓」</b>：迁移（V25）会给存量门店建默认仓，但新建门店/新业态上线
 * 不会自动补；库存写入必须先有仓库才能落余额，因此在写入路径上按 {@code code = DEFAULT} 懒创建
 * 是必要的。并发创建由唯一键兜底：抢输的事务重新读取即可，绝不能建出两个默认仓。
 *
 * <p>本端口不暴露任何持久化类型。
 */
public interface InventoryWarehouseRepository {

  /** 按标识读取仓库（当前租户）；不存在返回空。 */
  Optional<InventoryWarehouse> findById(long tenantId, Long warehouseId);

  /**
   * 取门店默认仓：{@code code = DEFAULT} 且启用，按 id 升序取第一条。
   *
   * @param businessType 业态；{@code null} 时只按门店 + 默认仓编码定位（旧上下文兼容读）
   */
  Optional<InventoryWarehouse> findDefault(long tenantId, Long storeId, String businessType);

  /** 按门店列出仓库（含默认仓；用于库存页的仓选择与作用域校验）。 */
  List<InventoryWarehouse> findByStore(long tenantId, Long storeId, String businessType);

  /**
   * 插入仓库，返回落库后的聚合；唯一键冲突必须抛出（由调用方按「重新读取默认仓」处理）。
   */
  InventoryWarehouse insert(InventoryWarehouse warehouse);
}
