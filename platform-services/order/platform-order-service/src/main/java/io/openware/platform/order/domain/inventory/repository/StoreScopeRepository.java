package io.openware.platform.order.domain.inventory.repository;

import java.util.Optional;

/**
 * 门店作用域快照的窄读端口（跨门店库存写入时校验「organization_id = store.organization_id、
 * business_type = store.business_type」）。
 *
 * <p><b>为什么需要</b>：{@code TenantContext} 只描述**当前**门店。跨门店/跨业态调拨的目标门店
 * 作用域必须来自门店权威数据，不能让调用方随便声明一个业态就把库存写进错的组织。
 * 该端口是只读窄查询，读取租户域的门店快照（{@code tnt_store}），不写任何门店数据。
 *
 * <p>{@link StoreScope#organizationId()} / {@link StoreScope#businessType()} 与 V24 回填
 * {@code ord_inventory_stock.organization_id/business_type} 的口径一致：
 * {@code organization_id = store.organization_id}、{@code business_type = store.business_type}。
 */
public interface StoreScopeRepository {

  /** 按租户 + 门店读取启用中的门店作用域快照；不存在 / 已停用返回空。 */
  Optional<StoreScope> findActive(long tenantId, Long storeId);

  /**
   * 门店作用域快照。
   *
   * @param tenantId       租户
   * @param storeId        门店
   * @param organizationId 门店所属组织（库存组织的权威来源）
   * @param businessType   门店业态（HOTEL / KTV / ...）
   * @param status         ACTIVE / SUSPENDED / CLOSED
   */
  record StoreScope(long tenantId, Long storeId, Long organizationId, String businessType, String status) {

    /** 是否启用（只有启用门店可接收库存写入）。 */
    public boolean active() {
      return "ACTIVE".equals(status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT));
    }
  }
}
