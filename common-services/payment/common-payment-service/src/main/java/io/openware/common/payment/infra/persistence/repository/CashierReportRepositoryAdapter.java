package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.cashier.model.ConfirmedCollectLine;
import io.openware.common.payment.domain.cashier.model.RefundCurrencyAggregate;
import io.openware.common.payment.domain.cashier.repository.CashierReportRepository;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.mapper.RefundMapper;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link CashierReportRepository} 的持久化适配器。
 *
 * <p>四条查询里两条是**跨表聚合**（收款经 {@code ord_order} 定位门店、退款按币种分组），
 * 用 Wrapper 表达不了，因此复用 mapper 的专用语句，由本类把行对象翻译成领域投影。
 *
 * <p>区间一律**左闭右开**：窗口端点是班次/营业日边界，闭区间会把同一笔算进相邻两天或两班。
 */
@Repository
public class CashierReportRepositoryAdapter implements CashierReportRepository {

  private final PayIntentMapper payIntentMapper;
  private final PayTransactionMapper payTransactionMapper;
  private final PayCollectMapper payCollectMapper;
  private final RefundMapper refundMapper;

  public CashierReportRepositoryAdapter(PayIntentMapper payIntentMapper,
                                        PayTransactionMapper payTransactionMapper,
                                        PayCollectMapper payCollectMapper,
                                        RefundMapper refundMapper) {
    this.payIntentMapper = payIntentMapper;
    this.payTransactionMapper = payTransactionMapper;
    this.payCollectMapper = payCollectMapper;
    this.refundMapper = refundMapper;
  }

  @Override
  public BigDecimal sumCashSucceeded(Long tenantId, Long storeId, String currencyCode, LocalDateTime from,
                                     LocalDateTime to) {
    BigDecimal sum = payIntentMapper.sumCashSucceeded(tenantId, storeId, currencyCode, from, to);
    // 无记录时归一为零：调用方（开班对账）不必判空。
    return sum == null ? BigDecimal.ZERO : sum;
  }

  @Override
  public List<PaymentIntent> listSucceededIntentsBetween(Long tenantId, Long storeId, LocalDateTime from,
                                                         LocalDateTime to) {
    return payIntentMapper.selectList(Wrappers.<PayIntentPo>lambdaQuery()
            .eq(PayIntentPo::getTenantId, tenantId)
            .eq(PayIntentPo::getStoreId, storeId)
            .eq(PayIntentPo::getStatus, PaymentIntent.STATUS_SUCCEEDED)
            .ge(from != null, PayIntentPo::getCreatedAt, from)
            .lt(to != null, PayIntentPo::getCreatedAt, to)
            .orderByAsc(PayIntentPo::getCreatedAt)
            .orderByAsc(PayIntentPo::getId))
        .stream().map(CashierReportRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<PaymentTransaction> listTransactionsBetween(Long tenantId, Long storeId, LocalDateTime from,
                                                          LocalDateTime to) {
    return payTransactionMapper.selectList(Wrappers.<PayTransactionPo>lambdaQuery()
            .eq(PayTransactionPo::getTenantId, tenantId)
            .eq(PayTransactionPo::getStoreId, storeId)
            .ge(from != null, PayTransactionPo::getOccurredAt, from)
            .lt(to != null, PayTransactionPo::getOccurredAt, to)
            .orderByAsc(PayTransactionPo::getOccurredAt)
            .orderByAsc(PayTransactionPo::getId))
        .stream().map(CashierReportRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<ConfirmedCollectLine> listConfirmedCollects(Long tenantId, Long storeId, LocalDateTime from,
                                                          LocalDateTime to) {
    return payCollectMapper.selectConfirmedByStoreAndWindow(tenantId, storeId, from, to).stream()
        .map(row -> new ConfirmedCollectLine(row.getCurrencyCode(), row.getResponseJson()))
        .toList();
  }

  @Override
  public List<RefundCurrencyAggregate> summarizeRefunds(Long tenantId, Long storeId, LocalDateTime from,
                                                        LocalDateTime to) {
    return refundMapper.sumRefundedByCurrency(tenantId, storeId, from, to).stream()
        .map(row -> new RefundCurrencyAggregate(row.getCurrencyCode(), row.getRefundCount(),
            row.getRefundAmount()))
        .toList();
  }

  private static PaymentIntent toDomain(PayIntentPo po) {
    return new PaymentIntent(po.getId(), po.getTenantId(), po.getStoreId(), po.getOrderId(),
        po.getMerchantAccountId(), po.getProvider(), po.getPaymentMethod(), po.getAmount(),
        po.getCurrencyCode(), po.getStatus(), po.getIdempotencyKey(), po.getExpiresAt(),
        po.getCreatedAt(), po.getUpdatedAt());
  }

  private static PaymentTransaction toDomain(PayTransactionPo po) {
    return new PaymentTransaction(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getPaymentIntentId(), po.getProvider(), po.getProviderTransactionNo(),
        po.getProviderPayloadDigest(), po.getAmount(), po.getCurrencyCode(), po.getExchangeRate(),
        po.getFeeAmount(), po.getStatus(), po.getOccurredAt(), po.getRawReference());
  }
}
