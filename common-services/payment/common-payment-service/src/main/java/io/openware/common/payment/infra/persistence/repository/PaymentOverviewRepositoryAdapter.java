package io.openware.common.payment.infra.persistence.repository;

import io.openware.common.payment.domain.cashier.model.RefundCurrencyAggregate;
import io.openware.common.payment.domain.overview.repository.PaymentOverviewRepository;
import io.openware.common.payment.infra.persistence.mapper.PaymentOverviewMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link PaymentOverviewRepository} 的持久化适配器。
 *
 * <p>两条查询都是动态条件（门店集合可选）的跨表聚合，用 Wrapper 表达不了，因此复用 mapper 的专用语句；
 * 本类是行对象与领域投影之间**唯一的翻译点**。
 */
@Repository
public class PaymentOverviewRepositoryAdapter implements PaymentOverviewRepository {

  private final PaymentOverviewMapper mapper;

  public PaymentOverviewRepositoryAdapter(PaymentOverviewMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<TransactionSummary> summarizeSucceededTransactions(Long tenantId, List<Long> storeIds,
                                                                LocalDateTime from, LocalDateTime to) {
    return mapper.selectSucceeded(tenantId, storeIds, from, to).stream()
        .map(row -> new TransactionSummary(row.getProvider(), row.getCurrencyCode(),
            row.getTransactionCount(), row.getAmount()))
        .toList();
  }

  @Override
  public List<RefundCurrencyAggregate> summarizeRefunds(Long tenantId, List<Long> storeIds,
                                                        LocalDateTime from, LocalDateTime to) {
    return mapper.selectRefunded(tenantId, storeIds, from, to).stream()
        .map(row -> new RefundCurrencyAggregate(row.getCurrencyCode(), row.getRefundCount(),
            row.getAmount()))
        .toList();
  }
}
