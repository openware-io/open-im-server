package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.mapper.PaymentOverviewMapper;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Payment 总部汇总用例；控制器不直接依赖持久化 Mapper。 */
@Service
public class PaymentOverviewApplicationService {
  private final PaymentOverviewMapper mapper;

  public PaymentOverviewApplicationService(PaymentOverviewMapper mapper) {
    this.mapper = mapper;
  }

  public List<PaymentOverviewRow> query(long tenantId, List<Long> storeIds,
      LocalDateTime from, LocalDateTime to) {
    return mapper.selectSucceeded(tenantId, storeIds, from, to).stream()
        .map(row -> new PaymentOverviewRow(row.getProvider(), row.getCurrencyCode(),
            row.getTransactionCount(), row.getAmount())).toList();
  }

  public record PaymentOverviewRow(String provider, String currencyCode, long transactionCount,
                                   BigDecimal amount) {}
}
