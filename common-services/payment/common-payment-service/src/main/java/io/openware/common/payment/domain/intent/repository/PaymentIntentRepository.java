package io.openware.common.payment.domain.intent.repository;

import io.openware.common.payment.domain.intent.model.PaymentIntent;
import java.util.Optional;

/**
 * 支付意图仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PaymentIntentRepository {

  /** 按标识读取支付意图。 */
  Optional<PaymentIntent> findById(Long intentId);

  /** 按幂等键读取支付意图（写入幂等判据）。 */
  Optional<PaymentIntent> findByIdempotencyKey(Long tenantId, String idempotencyKey);

  /** 新增支付意图，返回落库后的聚合（含回填主键）。 */
  PaymentIntent insert(PaymentIntent intent);

  /** 按支付发生时刻 {@code createdAt} 的闭区间列出支付意图（时间倒序），两端为 {@code null} 表示该端不筛。 */
  java.util.List<PaymentIntent> listByCreatedAtBetween(java.time.LocalDateTime fromInclusive,
                                                       java.time.LocalDateTime toInclusive);

  /** 按订单（单租户、批量）列出支付意图（按创建时间、标识升序）。 */
  java.util.List<PaymentIntent> listByOrderIds(Long tenantId, java.util.List<Long> orderIds);
}
