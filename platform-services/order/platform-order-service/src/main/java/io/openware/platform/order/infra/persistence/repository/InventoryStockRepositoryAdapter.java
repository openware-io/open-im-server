package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.order.domain.inventory.model.InventoryScope;
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
 * {@link #deductAvailable} 直接委托 mapper 的原子 UPDATE（{@code SET on_hand_qty = on_hand_qty - n
 * WHERE ... AND on_hand_qty - reserved_qty >= n}），返回受影响行数是否为 1，因此并发下不可能把库存扣成负数；
 * {@link #increaseOnHand} 把「加现有量」与「落库新的移动加权平均成本」放在同一条语句里
 * （{@code avg_cost = #{avgCost}}），调用方在 {@link #findForUpdate} 的行锁内算好新成本。
 * <b>这三条语句的形状是刻意保留的</b>（SQL 在 {@code InventoryStockMapper} 的注解里，本适配器不改写、
 * 不拆成「先读后判再写回」），{@code InventoryStockRepositoryAdapterTest} 会逐条钉住它们。
 *
 * <p><b>作用域（V24/V25）</b>：所有按余额定位的方法都把 {@link InventoryScope} 拆成
 * tenant / organization / business_type / store / warehouse 五段下发；{@code organization_id} 只作为
 * 归属快照参与写入与过滤，不进入唯一键（唯一键是 tenant+business+store+warehouse+material）。
 *
 * <p><b>审计列的归属</b>：{@code created_by / updated_by / updated_at} 是记账信息，领域模型不承载它们；
 * {@code created_at} 是 {@code NOT NULL} 列，由 {@link #save} 在插入时填当前时刻。
 *
 * <p><b>没有整行写回</b>：{@link #save} 只插入。库存变更只能走上面两条原子语句——见端口的说明。
 */
@Repository
public class InventoryStockRepositoryAdapter implements InventoryStockRepository {

  private final InventoryStockMapper mapper;

  public InventoryStockRepositoryAdapter(InventoryStockMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<InventoryStock> findForUpdate(InventoryScope scope, Long materialId) {
    return Optional.ofNullable(mapper.selectForUpdate(scope.tenantId(), scope.organizationId(),
            scope.businessType(), scope.storeId(), scope.warehouseId(), materialId))
        .map(this::toDomain);
  }

  @Override
  public Optional<InventoryStock> find(InventoryScope scope, Long materialId) {
    return Optional.ofNullable(mapper.selectOne(filterOf(scope)
        .eq(InventoryStockPo::getMaterialId, materialId))).map(this::toDomain);
  }

  @Override
  public List<InventoryStock> findByMaterials(InventoryScope storeScope, List<Long> materialIds) {
    if (materialIds == null || materialIds.isEmpty()) {
      // 空集合直接返回空列表：绝不能下发 IN ()（非法 SQL），也不该为空集合白查一次库。
      return List.of();
    }
    return mapper.selectList(filterOf(storeScope).in(InventoryStockPo::getMaterialId, materialIds))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public List<InventoryStock> findByStore(InventoryScope storeScope) {
    return mapper.selectByStore(storeScope.tenantId(), storeScope.businessType(), storeScope.storeId(),
            storeScope.warehouseId()).stream().map(this::toDomain).toList();
  }

  @Override
  public boolean deductAvailable(InventoryScope scope, Long materialId, BigDecimal quantity,
      LocalDateTime updatedAt) {
    // 单条条件更新：WHERE 中带 on_hand_qty - reserved_qty >= quantity，因此返回值就是权威结论。
    return mapper.deductAvailable(scope.tenantId(), scope.organizationId(), scope.businessType(),
        scope.storeId(), scope.warehouseId(), materialId, quantity, updatedAt) > 0;
  }

  @Override
  public int increaseOnHand(InventoryScope scope, Long materialId, BigDecimal quantity, BigDecimal avgCost,
      String currencyCode, LocalDateTime updatedAt) {
    return mapper.increaseOnHand(scope.tenantId(), scope.organizationId(), scope.businessType(),
        scope.storeId(), scope.warehouseId(), materialId, quantity, avgCost, currencyCode, updatedAt);
  }

  @Override
  public InventoryStock save(InventoryStock stock) {
    InventoryStockPo po = new InventoryStockPo();
    po.setId(stock.id());
    po.setTenantId(stock.tenantId());
    po.setOrganizationId(stock.organizationId());
    po.setBusinessType(stock.businessType());
    po.setStoreId(stock.storeId());
    po.setWarehouseId(stock.warehouseId());
    po.setMaterialId(stock.materialId());
    po.setOnHandQty(stock.onHandQty());
    po.setReservedQty(stock.reservedQty());
    po.setAvgCost(stock.avgCost());
    po.setCurrencyCode(stock.currencyCode());
    po.setRevision(stock.revision());
    // 审计时间由适配器兜底（领域模型不承载记账列）：created_at 是 NOT NULL，插入时必须给值。
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 只插入，不提供「按 id 覆盖写余额行」的分支：库存的每次变更都必须走 deductAvailable/increaseOnHand
    // 的原子条件语句，整行写回会把防超卖重新变成「先读后判再写回」的竞态（见端口说明）。
    // currency_code 为 null 时 NOT_NULL 策略会跳过该列，由 DDL 的 DEFAULT 'USD' 填充（与迁移前一致）。
    mapper.insert(po);
    return toDomain(po);
  }

  /**
   * 读路径的公共过滤：租户 + 门店 +（可选）仓库/业态/组织。
   *
   * <p>仓库为空时按门店聚合（物料列表的结存汇总）；业态/组织为空时不做过滤（旧上下文兼容读）。
   */
  private static LambdaQueryWrapper<InventoryStockPo> filterOf(InventoryScope scope) {
    LambdaQueryWrapper<InventoryStockPo> query = new LambdaQueryWrapper<InventoryStockPo>()
        .eq(InventoryStockPo::getTenantId, scope.tenantId())
        .eq(InventoryStockPo::getStoreId, scope.storeId());
    if (scope.warehouseId() != null) {
      query.eq(InventoryStockPo::getWarehouseId, scope.warehouseId());
    }
    if (scope.businessType() != null) {
      query.eq(InventoryStockPo::getBusinessType, scope.businessType());
    }
    if (scope.organizationId() != null) {
      query.eq(InventoryStockPo::getOrganizationId, scope.organizationId());
    }
    return query;
  }

  private InventoryStock toDomain(InventoryStockPo po) {
    if (po == null) {
      return null;
    }
    return new InventoryStock(
        po.getId(), po.getTenantId(), po.getOrganizationId(), po.getBusinessType(), po.getStoreId(),
        po.getWarehouseId(), po.getMaterialId(), po.getOnHandQty(), po.getReservedQty(), po.getAvgCost(),
        po.getCurrencyCode(), po.getRevision());
  }
}
