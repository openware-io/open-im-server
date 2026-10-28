package io.openware.platform.order.domain.inventory.model;

import java.util.Locale;
import java.util.Objects;

/**
 * 库存作用域（通用库存域的唯一「作用域」表达，酒店 / KTV / 零售共用）。
 *
 * <p><b>库存域组成</b>：Material（物料档案）/ Warehouse（仓/库位）/ Stock（余额）/
 * StockTransaction（流水）/ Valuation（移动加权平均成本）同属**公共支撑域**，不按业态拆分：
 * 酒店商品（水、毛巾、一次性用品、迷你吧）与 KTV 商品（酒水、小食）共用同一套表、同一套服务。
 * 仓库不是酒店域的「房量」，库存也不绑定房间；房态 / 房量属于酒店核心域（见
 * {@code ORDER_ROOM_INVENTORY_TARGET.md}）。
 *
 * <p><b>作用域键（V25）</b>：{@code tenant_id + business_type + store_id + warehouse_id + material_id}。
 * 仓库是真正的库存维度（{@code ord_inventory_warehouse}）：每个「租户 + 业态 + 门店」至少有一个
 * 默认仓（{@code code = DEFAULT}），库存余额与流水都必须落在具体仓库上。
 *
 * <p><b>{@code organizationId} 不是唯一键的一部分</b>：它是归属 / 报表快照（门店所属组织），
 * 只用于跨组织汇总与审计追溯。写入时以签名上下文里的组织快照为准，与请求声明不一致即拒绝，
 * 因此同一 (租户, 业态, 门店, 仓库, 物料) 永远只有一行余额。
 *
 * <p><b>为什么 {@code warehouseId} 可为空</b>：门店级只读查询（物料列表的结存汇总、门店成本报表、
 * 流水列表未指定仓库时）按「门店 + 业态」过滤即可，不需要钉到某一仓；而**所有写路径**都必须先解析出
 * 默认仓并使用带仓库的作用域（见 {@link #withWarehouse(Long)}）。这样作用域模型既能表达
 * 「一店多仓」的聚合读，也不会让写入落在一个没有仓库的余额行上。
 *
 * @param tenantId       租户（必填，&gt; 0）
 * @param organizationId 组织（门店所属组织快照；库存写入要求非空）
 * @param businessType   业态快照（HOTEL / KTV / ...）；写入要求非空，读取兼容旧上下文时可为 {@code null}
 * @param storeId        门店
 * @param warehouseId    仓/库位；门店级聚合读为 {@code null}
 */
public record InventoryScope(long tenantId, Long organizationId, String businessType, Long storeId,
    Long warehouseId) {

  public InventoryScope {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenantId 必须大于 0");
    }
    Objects.requireNonNull(storeId, "storeId 不能为空");
    businessType = normalizeBusinessType(businessType);
  }

  /** 门店级作用域（不钉仓库；用于物料、聚合报表、未指定仓库的列表读）。 */
  public static InventoryScope store(Long tenantId, Long organizationId, String businessType, Long storeId) {
    return new InventoryScope(tenantId, organizationId, businessType, storeId, null);
  }

  /** 门店 + 仓库作用域（库存余额/写路径的完整作用域）。 */
  public static InventoryScope warehouse(long tenantId, Long organizationId, String businessType, Long storeId,
      Long warehouseId) {
    return new InventoryScope(tenantId, organizationId, businessType, storeId,
        Objects.requireNonNull(warehouseId, "warehouseId 不能为空"));
  }

  /** 业态归一：去除首尾空白并转大写（DDL 口径为大写枚举）；空白 = null。 */
  public static String normalizeBusinessType(String businessType) {
    if (businessType == null || businessType.isBlank()) {
      return null;
    }
    return businessType.trim().toUpperCase(Locale.ROOT);
  }

  /** 是否已钉到具体仓库（库存余额/流水的写入与单仓读）。 */
  public boolean hasWarehouse() {
    return warehouseId != null;
  }

  /** 当前作用域是否带业态快照（旧上下文兼容态为 false；写路径要求 true）。 */
  public boolean hasBusinessType() {
    return businessType != null;
  }

  /** 补上仓库维度（返回新实例，不修改原记录）。 */
  public InventoryScope withWarehouse(Long newWarehouseId) {
    return new InventoryScope(tenantId, organizationId, businessType, storeId,
        Objects.requireNonNull(newWarehouseId, "warehouseId 不能为空"));
  }

  /**
   * 同一 (租户, 门店) 内的作用域是否相容（幂等重放与权限校验口径）。
   *
   * <p>{@code null} 视为「未声明」：旧上下文或未传仓库的读请求不会因为缺少某一段而被判成越权，
   * 但显式声明的两段不同即拒绝。组织 / 业态 / 仓库必须逐段一致。
   */
  public boolean compatibleWith(InventoryScope other) {
    if (other == null) {
      return false;
    }
    if (tenantId != other.tenantId || !storeId.equals(other.storeId)) {
      return false;
    }
    return sameOrAbsent(organizationId, other.organizationId)
        && sameOrAbsent(businessType, other.businessType)
        && sameOrAbsent(warehouseId, other.warehouseId);
  }

  private static boolean sameOrAbsent(Object left, Object right) {
    return left == null || right == null || left.equals(right);
  }
}
