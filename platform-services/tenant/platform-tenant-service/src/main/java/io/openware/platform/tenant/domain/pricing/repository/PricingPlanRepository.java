package io.openware.platform.tenant.domain.pricing.repository;

import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import java.util.List;
import java.util.Optional;

/**
 * 计价方案仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PricingPlanRepository {

  /**
   * 按作用域读取方案：租户 + 门店 + 业态 + 资源类型。
   *
   * <p>{@code businessType} 必须是**已归一**的取值（空串表示不限业态），
   * {@code storeId} 为 0 表示租户级。四元组对应 {@code uk_tnt_pricing_plan_scope_type}。
   */
  Optional<Persisted<PricingPlan>> findByScope(Long tenantId, Long storeId, String businessType,
                                               String resourceType);

  /**
   * 按幂等键读取方案：租户 + 幂等键 + 资源类型（对应 {@code uk_tnt_pricing_plan_idempotency}）。
   *
   * <p>资源类型参与判据：同一个幂等键允许为不同资源类型各写一次。
   */
  Optional<Persisted<PricingPlan>> findByIdempotencyKey(Long tenantId, String idempotencyKey,
                                                        String resourceType);

  /** 列出某租户下生效中的方案（按标识倒序），可按业态筛选（{@code null} 表示不筛）。 */
  List<Persisted<PricingPlan>> listActiveByTenant(Long tenantId, String businessType);

  /** 列出某租户下生效中的**全部**方案（供作用域解析在内存中挑选）。 */
  List<Persisted<PricingPlan>> listActiveByTenant(Long tenantId);

  /** 新增方案（版本从 0 起）；审计列由适配器按 {@code occurredAt} 填充。 */
  Persisted<PricingPlan> insert(PricingPlan plan, java.time.LocalDateTime occurredAt);

  /**
   * 乐观锁更新：仅当库中版本等于 {@code expectedVersion} 时写入，并把版本自增 1。
   *
   * <p><b>必须由单条条件 UPDATE 完成</b>，形如
   * {@code UPDATE tnt_pricing_plan SET ..., version = version + 1 WHERE id = ? AND version = ?}，
   * 受影响行数为 0 即表示已被并发修改。
   *
   * @return 更新后的行；CAS 失败返回 empty
   */
  Optional<Persisted<PricingPlan>> updateWithVersion(PricingPlan plan, int expectedVersion,
                                                     java.time.LocalDateTime occurredAt);
}
