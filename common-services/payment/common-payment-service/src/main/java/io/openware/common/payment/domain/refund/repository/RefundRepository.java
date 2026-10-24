package io.openware.common.payment.domain.refund.repository;

import io.openware.common.payment.domain.channel.repository.Persisted;
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
  Optional<Persisted<Refund>> findById(Long refundId);

  /** 按申请发生时刻 {@code createdAt} 的闭区间列出退款申请（时间倒序），两端为 {@code null} 表示该端不筛。 */
  List<Persisted<Refund>> listByCreatedAtBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive);

  /** 按订单（单租户、批量）列出退款单（按创建时间、标识升序）。 */
  List<Persisted<Refund>> listByOrderIds(Long tenantId, List<Long> orderIds);

  /** 新增退款单；审计列由适配器按 {@code occurredAt} 填充。 */
  Persisted<Refund> insert(Refund refund, java.time.LocalDateTime occurredAt);

  /**
   * 状态守卫的条件更新：仅当库中状态仍等于 {@code expectedStatus} 时写入。
   *
   * <p><b>由单条 {@code UPDATE ... WHERE id = ? AND status = ?} 完成</b>，这是本表唯一的并发保护手段——
   * {@code pay_refund} **没有版本列**，而审批是"PENDING → APPROVED / REJECTED"的状态机。
   *
   * <p>受影响 0 行表示状态已被他人改变，调用方应报冲突而不是当作成功。
   *
   * @return 更新后的行；CAS 失败返回 empty
   */
  Optional<Persisted<Refund>> updateStatusGuarded(Refund refund, String expectedStatus,
                                                  java.time.LocalDateTime occurredAt);

  /** 同订单仍在 PENDING/APPROVED 的退款金额（含已批准未退），用于并发可退上限。 */
  java.math.BigDecimal sumActiveAmount(Long tenantId, Long orderId, Long excludeRefundId);
}
