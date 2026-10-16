package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import io.openware.platform.order.domain.inventory.repository.InventoryTransactionRepository;
import io.openware.platform.order.infra.persistence.mapper.InventoryTransactionMapper;
import io.openware.platform.order.infra.persistence.po.InventoryTransactionPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link InventoryTransactionRepository} 的持久化适配器。
 *
 * <p><b>只追加</b>：本适配器只有 {@code insert} 一条写路径，没有 {@code updateById}/{@code deleteById}
 * ——库存流水是账目，事后改一条会让成本追溯与幂等重放失去依据（端口形状保证的结构属性，不是约定）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code ord_inventory_transaction}）：id / tenant_id / store_id /
 * material_id / transaction_type / quantity_delta / quantity_before / quantity_after / unit_cost / total_cost /
 * currency_code / source_type / source_id / idempotency_key / reason / created_by / created_at /
 * updated_by / updated_at。持久化对象上没有 {@code @TableField(exist = false)} 字段——逐字段核对过，
 * 因此这里不需要像物料适配器那样跳过展示字段。
 *
 * <p><b>审计列</b>：本表是追加型流水，{@code created_by}/{@code updated_by}/{@code updated_at} 是记账信息
 * （DDL 里 {@code updated_at} 由库的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 填、{@code updated_by} 缺省 0），
 * 领域模型不承载它们，适配器也不写：迁移前的实现同样没写这三列。
 * {@code created_at}（发生时间）是真实业务时间列，在领域模型上，适配器只在其为 {@code null} 时兜底为当前时刻。
 */
@Repository
public class InventoryTransactionRepositoryAdapter implements InventoryTransactionRepository {

  private final InventoryTransactionMapper mapper;

  public InventoryTransactionRepositoryAdapter(InventoryTransactionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<InventoryTransaction> findByIdempotency(Long tenantId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      // 空键不查库：绝不用空串去匹配别人的流水。
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.findByIdempotency(tenantId, idempotencyKey))
        .map(InventoryTransactionRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<InventoryTransaction> findLatestOutbound(Long tenantId, Long storeId, Long materialId,
      String sourceType, String sourceId) {
    return Optional.ofNullable(mapper.findLatestOutbound(tenantId, storeId, materialId, sourceType, sourceId))
        .map(InventoryTransactionRepositoryAdapter::toDomain);
  }

  @Override
  public InventoryTransaction append(InventoryTransaction transaction) {
    InventoryTransactionPo po = toPo(transaction);
    if (po.getCreatedAt() == null) {
      po.setCreatedAt(LocalDateTime.now());
    }
    // 撞唯一键（tenant_id, idempotency_key）必须抛出：吞掉它会让调用方以为流水已落但实际没有。
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public TransactionPage findPage(TransactionQuery query) {
    long total = mapper.selectCount(filterOf(query));
    if (total == 0) {
      return new TransactionPage(List.of(), 0L);
    }
    // 偏移 + 上限由端口表达，这里翻译成 LIMIT/OFFSET；分页对象（Page/IPage）不进入端口签名。
    List<InventoryTransaction> transactions = mapper.selectList(filterOf(query)
            .orderByDesc(InventoryTransactionPo::getId)
            .last("LIMIT " + Math.max(0L, query.limit()) + " OFFSET " + Math.max(0L, query.offset())))
        .stream().map(InventoryTransactionRepositoryAdapter::toDomain).toList();
    return new TransactionPage(transactions, total);
  }

  /** 分页/计数的同一套过滤条件（计数与取页必须同口径，否则 total 与 records 会对不上）。 */
  private static LambdaQueryWrapper<InventoryTransactionPo> filterOf(TransactionQuery query) {
    return Wrappers.<InventoryTransactionPo>lambdaQuery()
        .eq(InventoryTransactionPo::getTenantId, query.tenantId())
        .eq(InventoryTransactionPo::getStoreId, query.storeId())
        .eq(query.materialId() != null, InventoryTransactionPo::getMaterialId, query.materialId())
        .eq(query.transactionType() != null, InventoryTransactionPo::getTransactionType, query.transactionType())
        .eq(query.sourceType() != null, InventoryTransactionPo::getSourceType, query.sourceType())
        // 时间条件落在 created_at（本表没有独立的 occurred_at 列），不用函数包裹，保持索引可用。
        .ge(query.createdFrom() != null, InventoryTransactionPo::getCreatedAt, query.createdFrom())
        .le(query.createdTo() != null, InventoryTransactionPo::getCreatedAt, query.createdTo())
        // 关键字命中的物料 id 白名单：空集合由应用层提前返回空页，绝不会走到这里（IN () 是非法 SQL）。
        .in(query.materialIds() != null && !query.materialIds().isEmpty(),
            InventoryTransactionPo::getMaterialId, query.materialIds());
  }

  /** 领域模型 → 持久化对象（只写业务列与创建时间；审计列由库默认值填充）。 */
  static InventoryTransactionPo toPo(InventoryTransaction transaction) {
    InventoryTransactionPo po = new InventoryTransactionPo();
    po.setId(transaction.id());
    po.setTenantId(transaction.tenantId());
    po.setStoreId(transaction.storeId());
    po.setMaterialId(transaction.materialId());
    po.setTransactionType(transaction.transactionType());
    po.setQuantityDelta(transaction.quantityDelta());
    po.setQuantityBefore(transaction.quantityBefore());
    po.setQuantityAfter(transaction.quantityAfter());
    // 历史流水（升级前）三列为 NULL：null 不写列，落库即 NULL，不用 0 冒充真实成本。
    po.setUnitCost(transaction.unitCost());
    po.setTotalCost(transaction.totalCost());
    po.setCurrencyCode(transaction.currencyCode());
    po.setSourceType(transaction.sourceType());
    po.setSourceId(transaction.sourceId());
    po.setIdempotencyKey(transaction.idempotencyKey());
    po.setReason(transaction.reason());
    po.setCreatedAt(transaction.createdAt());
    return po;
  }

  /** 持久化对象 → 领域模型（只取业务列与发生时间）。 */
  static InventoryTransaction toDomain(InventoryTransactionPo po) {
    if (po == null) {
      return null;
    }
    return new InventoryTransaction(po.getId(), po.getTenantId(), po.getStoreId(), po.getMaterialId(),
        po.getTransactionType(), po.getQuantityDelta(), po.getQuantityBefore(), po.getQuantityAfter(),
        po.getUnitCost(), po.getTotalCost(), po.getCurrencyCode(), po.getSourceType(), po.getSourceId(),
        po.getIdempotencyKey(), po.getReason(), po.getCreatedAt());
  }
}
