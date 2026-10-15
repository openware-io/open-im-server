package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.order.domain.inventory.model.InventoryStock;
import io.openware.platform.order.domain.inventory.repository.InventoryStockRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryStockMapper;
import io.openware.platform.order.infra.persistence.po.InventoryStockPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link InventoryStockRepository} 的持久化适配器。
 *
 * <p>只做模型转换与委托，不承载业务判断：库存是否够扣由数据库条件更新决定，不由本层或应用层先读后判。
 * {@link #deductAvailable} 直接委托 mapper 的原子 UPDATE，返回受影响行数是否为 1，
 * 因此并发下不可能把库存扣成负数。
 */
@Repository
public class InventoryStockRepositoryAdapter implements InventoryStockRepository {

  private final InventoryStockMapper mapper;

  public InventoryStockRepositoryAdapter(InventoryStockMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<InventoryStock> findForUpdate(Long tenantId, Long storeId, Long materialId) {
    return Optional.ofNullable(mapper.selectForUpdate(tenantId, storeId, materialId)).map(this::toDomain);
  }

  @Override
  public Optional<InventoryStock> find(Long tenantId, Long storeId, Long materialId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, tenantId)
        .eq(InventoryStockPo::getStoreId, storeId)
        .eq(InventoryStockPo::getMaterialId, materialId))).map(this::toDomain);
  }

  @Override
  public List<InventoryStock> findByMaterials(Long tenantId, Long storeId, List<Long> materialIds) {
    return mapper.selectList(new LambdaQueryWrapper<InventoryStockPo>()
            .eq(InventoryStockPo::getTenantId, tenantId)
            .eq(InventoryStockPo::getStoreId, storeId)
            .in(InventoryStockPo::getMaterialId, materialIds))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public List<InventoryStock> findByStore(Long tenantId, Long storeId) {
    return mapper.selectByStore(tenantId, storeId).stream().map(this::toDomain).toList();
  }

  @Override
  public boolean deductAvailable(Long tenantId, Long storeId, Long materialId, BigDecimal quantity,
      LocalDateTime updatedAt) {
    // 单条条件更新：WHERE 中带 on_hand_qty - reserved_qty >= quantity，因此返回值就是权威结论。
    return mapper.deductAvailable(tenantId, storeId, materialId, quantity, updatedAt) > 0;
  }

  @Override
  public int increaseOnHand(Long tenantId, Long storeId, Long materialId, BigDecimal quantity,
      BigDecimal avgCost, String currencyCode, LocalDateTime updatedAt) {
    return mapper.increaseOnHand(tenantId, storeId, materialId, quantity, avgCost, currencyCode, updatedAt);
  }

  @Override
  public InventoryStock save(InventoryStock stock) {
    InventoryStockPo po = new InventoryStockPo();
    po.setId(stock.id());
    po.setTenantId(stock.tenantId());
    po.setStoreId(stock.storeId());
    po.setMaterialId(stock.materialId());
    po.setOnHandQty(stock.onHandQty());
    po.setReservedQty(stock.reservedQty());
    po.setAvgCost(stock.avgCost());
    po.setCurrencyCode(stock.currencyCode());
    po.setVersion(stock.version());
    po.setCreatedBy(stock.createdBy());
    po.setCreatedAt(stock.createdAt());
    po.setUpdatedBy(stock.updatedBy());
    po.setUpdatedAt(stock.updatedAt());
    if (po.getId() == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private InventoryStock toDomain(InventoryStockPo po) {
    if (po == null) {
      return null;
    }
    return new InventoryStock(
        po.getId(), po.getTenantId(), po.getStoreId(), po.getMaterialId(),
        po.getOnHandQty(), po.getReservedQty(), po.getAvgCost(), po.getCurrencyCode(),
        po.getVersion(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
