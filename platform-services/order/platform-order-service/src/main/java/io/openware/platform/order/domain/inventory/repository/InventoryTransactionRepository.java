package io.openware.platform.order.domain.inventory.repository;

import io.openware.platform.order.domain.inventory.model.InventoryScope;
import io.openware.platform.order.domain.inventory.model.InventoryTransaction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库存流水仓储端口。
 *
 * <p>不暴露 {@code LambdaQueryWrapper}、PO 或任何持久化类型；分页以「偏移 + 上限」与总数表达
 * （见 {@link TransactionPage}），适配器翻译成 LIMIT/OFFSET。
 *
 * <p><b>本表只追加</b>：端口上没有「改一条历史流水」的方法，这是结构性的保证，不是约定——
 * 库存流水是账目，事后改一条会让成本追溯与幂等重放失去依据。
 *
 * <p><b>作用域（V24/V25）</b>：流水落库携带 {@code organization_id / business_type / store_id /
 * warehouse_id}；查询收口为 {@link TransactionQuery}（门店 + 业态 + 可选仓库）。跨业态调拨会写两条
 * 流水（{@code TRANSFER_OUT} / {@code TRANSFER_IN}），各自落在自己的作用域里，用同一个
 * {@code sourceId} 关联。
 */
public interface InventoryTransactionRepository {

  /**
   * 按幂等键取流水（当前租户内唯一）。
   *
   * <p>库存变更的幂等入口：命中即代表这次变更已经发生过，调用方必须**原样返回首次流水**，
   * 不得重复扣减/入库。幂等键是租户级唯一，因此命中后调用方还要用
   * {@link InventoryScope#compatibleWith} 校验作用域，避免拿别的门店/业态的键重放。
   */
  Optional<InventoryTransaction> findByIdempotency(Long tenantId, String idempotencyKey);

  /**
   * 取某来源明细最近一次「出库结转」的流水（{@code transaction_type IN (CONSUME, ADJUST_OUT,
   * TRANSFER_OUT)}，按 id 倒序取最新一条）。
   *
   * <p>作废回补（REVERSE）的成本还原口径：按当时出库用的单价回补，而不是按回补时点的平均成本。
   */
  Optional<InventoryTransaction> findLatestOutbound(Long tenantId, Long storeId, Long materialId,
      String sourceType, String sourceId);

  /**
   * 追加一条流水，返回落库后的聚合（含回填主键与落库口径的 {@code createdAt}）。
   *
   * <p>幂等由唯一键 {@code uk_ord_inventory_transaction_idempotency(tenant_id, idempotency_key)} 兜底，
   * 撞键必须抛出：吞掉它会让调用方以为流水已落但实际没有。
   */
  InventoryTransaction append(InventoryTransaction transaction);

  /** 流水分页列表（当前页记录 + 命中总数），按 id 倒序（等价于发生时间倒序，同毫秒写入时也稳定）。 */
  TransactionPage findPage(TransactionQuery query);

  /**
   * 流水查询条件（业务语义的组合，不是查询语法）。
   *
   * @param tenantId        租户（必填）
   * @param storeId         门店（必填）
   * @param businessType    业态（作用域）；{@code null} = 不按业态过滤（旧上下文兼容读）
   * @param organizationId  组织快照；{@code null} = 不按组织过滤
   * @param warehouseId     仓库；{@code null} = 该门店所有仓
   * @param materialId      物料；非空时精确匹配
   * @param transactionType 变更类型；非空时精确匹配
   * @param sourceType      来源类型；非空时精确匹配
   * @param createdFrom     发生时间下界（含）；{@code null} = 不筛
   * @param createdTo       发生时间上界（含）；{@code null} = 不筛
   * @param materialIds     物料 id 白名单（关键字命中集合）；非空时按 {@code IN} 过滤，
   *                        **空集合**由调用方在进入本端口前就返回空页，绝不能下发 {@code IN ()}
   * @param offset          偏移量（从 0 起）
   * @param limit           本页上限
   */
  record TransactionQuery(
      Long tenantId,
      Long storeId,
      String businessType,
      Long organizationId,
      Long warehouseId,
      Long materialId,
      String transactionType,
      String sourceType,
      LocalDateTime createdFrom,
      LocalDateTime createdTo,
      List<Long> materialIds,
      long offset,
      long limit) {

    /** 兼容既有测试/调用的 10 参构造（不按组织/业态/仓库过滤）。 */
    public TransactionQuery(Long tenantId, Long storeId, Long materialId, String transactionType,
        String sourceType, LocalDateTime createdFrom, LocalDateTime createdTo, List<Long> materialIds,
        long offset, long limit) {
      this(tenantId, storeId, null, null, null, materialId, transactionType, sourceType, createdFrom, createdTo,
          materialIds, offset, limit);
    }

    /**
     * 门店级查询口径（不按组织/业态/仓库过滤）。
     *
     * <p>保留该工厂是为了兼容既有调用/测试；应用层的新写路径应使用
     * {@link #inScope(InventoryScope, Long, String, String, LocalDateTime, LocalDateTime, List, long, long)}。
     */
    public static TransactionQuery inStore(Long tenantId, Long storeId, Long materialId, String transactionType,
        String sourceType, LocalDateTime createdFrom, LocalDateTime createdTo, List<Long> materialIds,
        long offset, long limit) {
      return new TransactionQuery(tenantId, storeId, null, null, null, materialId, transactionType, sourceType,
          createdFrom, createdTo, materialIds, offset, limit);
    }

    /**
     * 作用域查询口径：门店 + 业态（+ 可选仓库/组织）。
     *
     * <p>时间条件落在 {@code created_at}（本表没有独立的 {@code occurred_at} 列，DDL 注释即为「发生时间」），
     * 不用函数包裹，保持索引可用。
     */
    public static TransactionQuery inScope(InventoryScope scope, Long materialId, String transactionType,
        String sourceType, LocalDateTime createdFrom, LocalDateTime createdTo, List<Long> materialIds,
        long offset, long limit) {
      return new TransactionQuery(scope.tenantId(), scope.storeId(), scope.businessType(),
          scope.organizationId(), scope.warehouseId(), materialId, transactionType, sourceType,
          createdFrom, createdTo, materialIds, offset, limit);
    }
  }

  /**
   * 流水一页。
   *
   * @param transactions 本页记录（按 id 倒序）
   * @param total        满足条件的总条数（不是本页条数）
   */
  record TransactionPage(List<InventoryTransaction> transactions, long total) {
  }
}
