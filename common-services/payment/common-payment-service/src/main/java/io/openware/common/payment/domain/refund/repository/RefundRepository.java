package io.openware.common.payment.domain.refund.repository;

import io.openware.common.payment.domain.refund.model.Refund;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 退款单仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface RefundRepository {

  /** 按标识读取退款单。 */
  Optional<Refund> findById(Long refundId);

  /** 按申请发生时刻 {@code createdAt} 的闭区间列出退款申请（时间倒序），两端为 {@code null} 表示该端不筛。 */
  List<Refund> listByCreatedAtBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive);

  /** 按订单（单租户、批量）列出退款单（按创建时间、标识升序）。 */
  List<Refund> listByOrderIds(Long tenantId, List<Long> orderIds);

  /** 新增退款单，返回落库后的聚合（含回填主键）。 */
  Refund insert(Refund refund);

  /**
   * 状态守卫的条件更新：仅当库中状态仍等于 {@code expectedStatus} 时写入，返回是否成功。
   *
   * <p><b>由单条 {@code UPDATE ... WHERE id = ? AND status = ?} 完成</b>，这是本表唯一的并发保护手段——
   * {@code pay_refund} **没有版本列**，而审批是"PENDING → APPROVED / REJECTED"的状态机。
   *
   * <p><b>为什么不能"先读出来判状态再无条件写回"</b>：两个并发的审批者会读到同一个 PENDING，
   * 双双通过判断，然后各自写入——**对退款而言这就是同一笔申请被批准两次**。状态守卫把判定与写入
   * 放进同一条语句，由数据库保证只有一个赢家。
   *
   * <p>受影响 0 行表示状态已被他人改变，调用方应报冲突而不是当作成功。
   */
  boolean updateStatusGuarded(Refund refund, String expectedStatus);
}
