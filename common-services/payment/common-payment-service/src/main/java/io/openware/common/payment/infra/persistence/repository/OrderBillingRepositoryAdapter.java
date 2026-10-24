package io.openware.common.payment.infra.persistence.repository;

import io.openware.common.payment.domain.billing.model.OrderReceivable;
import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.infra.persistence.mapper.OrderBillingMapper;
import io.openware.common.payment.infra.persistence.po.OrderBillingPo;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OrderBillingRepository} 的持久化适配器。
 *
 * <p>{@code findForUpdate} 复用 mapper 的 {@code FOR UPDATE} 语句：锁语义写在 SQL 里，
 * 无法用 Wrapper 表达，因此这条查询留在 mapper、由本层翻译成领域投影。
 *
 * <p>本类**不写审计字段**：{@code ord_order} 属于订单上下文，
 * 它的 {@code updated_at} 由 {@code markPaid} 的 SQL 自己维护（{@code CURRENT_TIMESTAMP(3)}）——
 * 支付域只提供"新的已收金额"，不应替订单域决定落账时间口径。
 */
@Repository
public class OrderBillingRepositoryAdapter implements OrderBillingRepository {

  private final OrderBillingMapper mapper;

  public OrderBillingRepositoryAdapter(OrderBillingMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<OrderReceivable> findForUpdate(Long tenantId, Long orderId) {
    OrderBillingPo po = mapper.selectForUpdate(tenantId, orderId);
    return Optional.ofNullable(po).map(OrderBillingRepositoryAdapter::toDomain);
  }

  @Override
  public boolean markPaid(Long tenantId, Long orderId, BigDecimal expectedPaid, BigDecimal newPaid,
                          BigDecimal totalAmount) {
    // 期望值是乐观条件：恰好一行表示这次落账成立；0 行表示订单已被其他收款改写或已不可收款。
    return mapper.markPaid(tenantId, orderId, expectedPaid, newPaid, totalAmount) == 1;
  }

  @Override
  public Optional<Long> findOwnedCustomerId(Long tenantId, Long orderId, Long accountId) {
    return Optional.ofNullable(mapper.selectOwnedCustomerId(tenantId, orderId, accountId));
  }

  @Override
  public Optional<String> findCurrencyCode(Long tenantId, Long orderId) {
    String currencyCode = mapper.selectCurrencyCode(tenantId, orderId);
    return currencyCode == null || currencyCode.isBlank() ? Optional.empty()
        : Optional.of(currencyCode);
  }

  @Override
  public boolean markRefunded(Long tenantId, Long orderId, BigDecimal refundAmount) {
    return mapper.markRefunded(tenantId, orderId, refundAmount) == 1;
  }

  private static OrderReceivable toDomain(OrderBillingPo po) {
    return new OrderReceivable(po.getId(), po.getStatus(), po.getTotalAmount(), po.getPaidAmount(),
        po.getCurrencyCode());
  }
}
