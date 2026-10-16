package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link PaymentTransactionRepository} 的持久化适配器。 */
@Repository
public class PaymentTransactionRepositoryAdapter implements PaymentTransactionRepository {

  private final PayTransactionMapper mapper;

  public PaymentTransactionRepositoryAdapter(PayTransactionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PaymentTransaction> listSucceededBetween(Long tenantId, LocalDateTime from, LocalDateTime to) {
    // 左闭右开：ge(from) + lt(to)。用 le(to) 会把午夜那一笔同时算进相邻两天。
    return mapper.selectList(Wrappers.<PayTransactionPo>lambdaQuery()
            .eq(PayTransactionPo::getTenantId, tenantId)
            .eq(PayTransactionPo::getStatus, PaymentTransaction.STATUS_SUCCEEDED)
            .ge(PayTransactionPo::getOccurredAt, from)
            .lt(PayTransactionPo::getOccurredAt, to))
        .stream().map(PaymentTransactionRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<PaymentTransaction> listByPaymentIntentIds(Long tenantId, List<Long> paymentIntentIds) {
    if (paymentIntentIds == null || paymentIntentIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<PayTransactionPo>lambdaQuery()
            .eq(PayTransactionPo::getTenantId, tenantId)
            .in(PayTransactionPo::getPaymentIntentId, paymentIntentIds)
            .orderByAsc(PayTransactionPo::getOccurredAt)
            .orderByAsc(PayTransactionPo::getId))
        .stream().map(PaymentTransactionRepositoryAdapter::toDomain).toList();
  }

  @Override
  public PaymentTransaction insert(PaymentTransaction transaction) {
    LocalDateTime now = LocalDateTime.now();
    PayTransactionPo po = new PayTransactionPo();
    po.setTenantId(transaction.tenantId());
    po.setStoreId(transaction.storeId());
    po.setBusinessType(transaction.businessType());
    po.setPaymentIntentId(transaction.paymentIntentId());
    po.setProvider(transaction.provider());
    po.setProviderTransactionNo(transaction.providerTransactionNo());
    po.setProviderPayloadDigest(transaction.providerPayloadDigest());
    po.setAmount(transaction.amount());
    po.setCurrencyCode(transaction.currencyCode());
    po.setExchangeRate(transaction.exchangeRate());
    po.setFeeAmount(transaction.feeAmount());
    po.setStatus(transaction.status());
    po.setOccurredAt(transaction.occurredAt() == null ? now : transaction.occurredAt());
    po.setRawReference(transaction.rawReference());
    // 审计字段非空且无默认值，必须由写入方提供（操作人列留默认 0）。
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  private static PaymentTransaction toDomain(PayTransactionPo po) {
    return new PaymentTransaction(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getPaymentIntentId(), po.getProvider(), po.getProviderTransactionNo(),
        po.getProviderPayloadDigest(), po.getAmount(), po.getCurrencyCode(), po.getExchangeRate(),
        po.getFeeAmount(), po.getStatus(), po.getOccurredAt(), po.getRawReference());
  }
}
