package io.openware.platform.order.domain.inventory.model;

/**
 * 仓库 / 库位（通用库存域，酒店与 KTV 共用）。
 *
 * <p>对应 {@code ord_inventory_warehouse}（V25）：{@code id / tenant_id / organization_id /
 * business_type / store_id / code / name / status / version / created_by / created_at / updated_by /
 * updated_at}。作用域唯一键是 {@code (tenant_id, business_type, store_id, code)}；同一门店同一业态
 * 至少有一个默认仓（{@link #DEFAULT_CODE}），库存余额/流水落在具体仓库上。
 *
 * <p><b>不是酒店房量</b>：仓库是商品的存放库位，和 {@code htl_room_availability}（房型×日期的可售
 * 数量）没有任何关系；酒店与 KTV 的商品库存共用这一张表。
 *
 * <p><b>{@code organizationId} 是归属 / 报表快照</b>，不进入仓库唯一键（见 {@link InventoryScope}）。
 *
 * <p>不可变记录：状态变更走仓储端口（{@code InventoryWarehouseRepository}），不在对象上改字段。
 *
 * @param id             仓库标识；{@code null} 表示尚未落库
 * @param tenantId       租户
 * @param organizationId 组织快照
 * @param businessType   业态快照（HOTEL / KTV / ...）
 * @param storeId        门店
 * @param code           门店内仓库编码（创建后不可改）；默认仓为 {@code DEFAULT}
 * @param name           仓库名称
 * @param status         ACTIVE / DISABLED
 * @param version        乐观锁版本号列
 */
public record InventoryWarehouse(
    Long id,
    Long tenantId,
    Long organizationId,
    String businessType,
    Long storeId,
    String code,
    String name,
    String status,
    Integer version) {

  /** 默认仓编码：每个「租户 + 业态 + 门店」一个，库存写路径在缺失时按此编码懒创建。 */
  public static final String DEFAULT_CODE = "DEFAULT";

  /** 默认仓名称。 */
  public static final String DEFAULT_NAME = "默认仓";

  /** 启用。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 停用。 */
  public static final String STATUS_DISABLED = "DISABLED";

  /** 是否启用（历史空状态按停用处理）。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status == null ? "" : status.trim());
  }

  /** 是否默认仓。 */
  public boolean defaultWarehouse() {
    return DEFAULT_CODE.equalsIgnoreCase(code == null ? "" : code.trim());
  }

  /** 是否已落库。 */
  public boolean persisted() {
    return id != null;
  }

  /** 新建默认仓（尚未落库）。 */
  public static InventoryWarehouse defaultDraft(InventoryScope storeScope) {
    return new InventoryWarehouse(null, storeScope.tenantId(), storeScope.organizationId(),
        storeScope.businessType(), storeScope.storeId(), DEFAULT_CODE, DEFAULT_NAME, STATUS_ACTIVE, 0);
  }

  /** 落库后回填主键（返回新实例）。 */
  public InventoryWarehouse withId(Long newId) {
    return new InventoryWarehouse(newId, tenantId, organizationId, businessType, storeId, code, name, status,
        version);
  }
}
