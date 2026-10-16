package io.openware.common.payment.domain.collect.repository;

import io.openware.common.payment.domain.collect.model.PayCollect;
import java.util.List;
import java.util.Optional;

/**
 * 收款单仓储端口（读写）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 *
 * <p><b>并发控制只能靠状态</b>：{@code pay_collect} **没有版本列**，
 * 而收款单是一个 {@code INIT → CONFIRMED/FAILED} 的状态机。因此本端口的状态迁移一律是
 * **带状态条件的单条 UPDATE**，判定与写入在同一条语句里由数据库保证原子性。
 * "先读出来判状态再无条件写回"会让两个并发请求双双通过判断——对收款而言就是**同一笔钱被确认两次**。
 */
public interface PayCollectRepository {

  /**
   * 按订单（单租户、批量）列出**已确认**收款（按创建时间、标识升序）。
   *
   * <p>只返回 {@code CONFIRMED}：{@code INIT}/{@code FAILED} 是尝试而非收款，
   * 把它们算进"已收金额"会让订单看起来收过钱。
   *
   * <p>租户边界由入参显式给定，不依赖拦截器兜底——这是平台侧的批量核对路径。
   */
  List<PayCollect> listConfirmedByOrderIds(Long tenantId, List<Long> orderIds);

  /** 按订单（单租户、批量）列出收款单（不限状态）。 */
  List<PayCollect> listByOrderIds(Long tenantId, List<Long> orderIds);

  /**
   * 按幂等键读取收款单。
   *
   * <p>唯一键 {@code uk_pay_collect_idem (tenant_id, idempotency_key)} 保证至多一条；
   * 该键上的唯一性同时是并发建单的兜底（见 {@link #insert}）。
   */
  Optional<PayCollect> findByIdempotencyKey(Long tenantId, String idempotencyKey);

  /**
   * 新增收款单，返回落库后的聚合（含回填主键）。
   *
   * <p>实现**必须把唯一键冲突原样抛出**（不吞、不转成"已存在则返回既有行"）：
   * 调用方需要区分"我建成了"与"别的并发请求先建成了"，后者要走"回读赢家并判定能否回放"的分支。
   */
  PayCollect insert(PayCollect collect);

  /**
   * 接管一条失败记录（同键重跑）：{@code FAILED → INIT} 且清空旧失败快照，返回是否抢到。
   *
   * <p>守卫条件是状态本身：受影响 0 行表示另一并发重试已经接管，调用方必须报冲突而不是继续重跑——
   * 继续跑会让两路重试同时对同一笔收款做跨服务扣减。
   */
  boolean claimFailedAsInit(Long collectId);

  /**
   * 写入状态与快照（{@code CONFIRMED} 或 {@code FAILED}），返回是否确实更新了一行。
   *
   * <p><b>为什么确认与失败共用一个方法</b>：两者是同一个动作——"把这次尝试的终态与它的快照落库"。
   * 分成两个方法会让状态机多出两条各自演化的写路径，而它们的失败模式完全一致
   * （都要求行存在、都要刷新 {@code updatedAt}）。调用方通过传入的状态与快照表达语义。
   */
  boolean updateStateAndSnapshot(PayCollect collect);
}
