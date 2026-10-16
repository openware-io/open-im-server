package io.openware.common.payment.application;

import io.openware.common.payment.domain.overview.repository.PaymentOverviewRepository;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Payment 总部汇总用例；控制器不直接依赖持久化 Mapper。 */
@Service
public class PaymentOverviewApplicationService {
  private final PaymentOverviewRepository overviewRepository;

  public PaymentOverviewApplicationService(PaymentOverviewRepository overviewRepository) {
    this.overviewRepository = overviewRepository;
  }

  public List<PaymentOverviewRow> query(long tenantId, List<Long> storeIds,
                                        LocalDateTime from, LocalDateTime to) {
    return overviewRepository.summarizeSucceededTransactions(tenantId, storeIds, from, to).stream()
        .map(row -> new PaymentOverviewRow(row.provider(), row.currencyCode(),
            row.transactionCount(), row.amount()))
        .toList();
  }

  public List<RefundOverviewRow> queryRefunds(long tenantId, List<Long> storeIds,
                                              LocalDateTime from, LocalDateTime to) {
    return overviewRepository.summarizeRefunds(tenantId, storeIds, from, to).stream()
        .map(row -> new RefundOverviewRow(row.currencyCode(), row.refundCount(), row.refundAmount()))
        .toList();
  }

  public record PaymentOverviewRow(String provider, String currencyCode, long transactionCount,
                                   BigDecimal amount) {}

  public record RefundOverviewRow(String currencyCode, long refundCount, BigDecimal amount) {}
}
