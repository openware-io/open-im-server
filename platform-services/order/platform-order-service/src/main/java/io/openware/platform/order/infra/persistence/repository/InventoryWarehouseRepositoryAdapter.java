package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.inventory.model.InventoryWarehouse;
import io.openware.platform.order.domain.inventory.repository.InventoryWarehouseRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryWarehouseMapper;
import io.openware.platform.order.infra.persistence.po.InventoryWarehousePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link InventoryWarehouseRepository} 的持久化适配器（{@code ord_inventory_warehouse}，V25）。
 *
 * <p>只做模型转换与委托。唯一键 {@code (tenant_id, business_type, store_id, code)} 的冲突必须抛出，
 * 由应用层按「并发建仓抢输 → 重新读取默认仓」处理；本适配器不吞冲突，否则会以为建出了两个仓。
 */
@Repository
public class InventoryWarehouseRepositoryAdapter implements InventoryWarehouseRepository {

  private final InventoryWarehouseMapper mapper;

  public InventoryWarehouseRepositoryAdapter(InventoryWarehouseMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<InventoryWarehouse> findById(long tenantId, Long warehouseId) {
    if (warehouseId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectById(tenantId, warehouseId)).map(this::toDomain);
  }

  @Override
  public Optional<InventoryWarehouse> findDefault(long tenantId, Long storeId, String businessType) {
    return Optional.ofNullable(mapper.selectDefault(tenantId, storeId, businessType)).map(this::toDomain);
  }

  @Override
  public List<InventoryWarehouse> findByStore(long tenantId, Long storeId, String businessType) {
    return mapper.selectByStore(tenantId, storeId, businessType).stream().map(this::toDomain).toList();
  }

  @Override
  public InventoryWarehouse insert(InventoryWarehouse warehouse) {
    InventoryWarehousePo po = new InventoryWarehousePo();
    po.setTenantId(warehouse.tenantId());
    po.setOrganizationId(warehouse.organizationId());
    po.setBusinessType(warehouse.businessType());
    po.setStoreId(warehouse.storeId());
    po.setCode(warehouse.code());
    po.setName(warehouse.name());
    po.setStatus(warehouse.status());
    po.setVersion(warehouse.version());
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 唯一键冲突必须向上抛：并发建默认仓时由应用层重新读取，不能吞掉变成两行。
    mapper.insert(po);
    return toDomain(po);
  }

  private InventoryWarehouse toDomain(InventoryWarehousePo po) {
    if (po == null) {
      return null;
    }
    return new InventoryWarehouse(po.getId(), po.getTenantId(), po.getOrganizationId(), po.getBusinessType(),
        po.getStoreId(), po.getCode(), po.getName(), po.getStatus(), po.getVersion());
  }
}
