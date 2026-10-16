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
  Optional<PricingPlan> findByScope(Long tenantId, Long storeId, String businessType, String resourceType);

  /**
   * 按幂等键读取方案：租户 + 幂等键 + 资源类型（对应 {@code uk_tnt_pricing_plan_idempotency}）。
   *
   * <p>资源类型参与判据：同一个幂等键允许为不同资源类型各写一次。
   */
  Optional<PricingPlan> findByIdempotencyKey(Long tenantId, String idempotencyKey, String resourceType);

  /** 列出某租户下生效中的方案（按标识倒序），可按业态筛选（{@code null} 表示不筛）。 */
  List<PricingPlan> listActiveByTenant(Long tenantId, String businessType);

  /** 列出某租户下生效中的**全部**方案（供作用域解析在内存中挑选）。 */
  List<PricingPlan> listActiveByTenant(Long tenantId);

  /** 新增方案（版本从 0 起）。 */
  PricingPlan insert(PricingPlan plan);

  /**
   * 乐观锁更新：仅当库中版本等于 {@code expectedVersion} 时写入，并把版本自增 1；返回是否成功。
   *
   * <p><b>必须由单条条件 UPDATE 完成</b>，形如
   * {@code UPDATE tnt_pricing_plan SET ..., version = version + 1 WHERE id = ? AND version = ?}，
   * 受影响行数为 0 即表示已被并发修改。
   *
   * <p><b>为什么不能"先读出来比对版本再无条件写回"</b>：两个并发请求会读到同一个版本、双双通过比对，
   * 然后各自无条件写回——后写者覆盖前者，且双方都收到成功响应。这类缺陷在单线程测试里看不出来，
   * 只会表现为线上"改价偶尔丢失"。CAS 把判定与写入放进同一条语句，由数据库保证原子性。
   */
  boolean updateWithVersion(PricingPlan plan, int expectedVersion);
}
