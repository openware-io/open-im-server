package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link PaymentIntentRepository} 的持久化适配器。 */
@Repository
public class PaymentIntentRepositoryAdapter implements PaymentIntentRepository {

  private final PayIntentMapper mapper;

  public PaymentIntentRepositoryAdapter(PayIntentMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<PaymentIntent> findById(Long intentId) {
    return Optional.ofNullable(mapper.selectById(intentId)).map(PaymentIntentRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<PaymentIntent> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<PayIntentPo>lambdaQuery()
            .eq(PayIntentPo::getTenantId, tenantId)
            .eq(PayIntentPo::getIdempotencyKey, idempotencyKey))
        .stream().findFirst().map(PaymentIntentRepositoryAdapter::toDomain);
  }

  @Override
  public PaymentIntent insert(PaymentIntent intent) {
    LocalDateTime now = LocalDateTime.now();
    PayIntentPo po = new PayIntentPo();
    po.setTenantId(intent.tenantId());
    po.setStoreId(intent.storeId());
    po.setOrderId(intent.orderId());
    po.setMerchantAccountId(intent.merchantAccountId());
    po.setProvider(intent.provider());
    po.setPaymentMethod(intent.paymentMethod());
    po.setAmount(intent.amount());
    po.setCurrencyCode(intent.currencyCode());
    po.setStatus(intent.status());
    po.setIdempotencyKey(intent.idempotencyKey());
    po.setExpiresAt(intent.expiresAt());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public java.util.List<PaymentIntent> listByCreatedAtBetween(java.time.LocalDateTime fromInclusive,
                                                              java.time.LocalDateTime toInclusive) {
    return mapper.selectList(Wrappers.<PayIntentPo>lambdaQuery()
            .ge(fromInclusive != null, PayIntentPo::getCreatedAt, fromInclusive)
            .le(toInclusive != null, PayIntentPo::getCreatedAt, toInclusive)
            .orderByDesc(PayIntentPo::getCreatedAt))
        .stream().map(PaymentIntentRepositoryAdapter::toDomain).toList();
  }

  @Override
  public java.util.List<PaymentIntent> listByOrderIds(Long tenantId, java.util.List<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return java.util.List.of();
    }
    return mapper.selectList(Wrappers.<PayIntentPo>lambdaQuery()
            .eq(PayIntentPo::getTenantId, tenantId)
            .in(PayIntentPo::getOrderId, orderIds)
            .orderByAsc(PayIntentPo::getCreatedAt)
            .orderByAsc(PayIntentPo::getId))
        .stream().map(PaymentIntentRepositoryAdapter::toDomain).toList();
  }

  private static PaymentIntent toDomain(PayIntentPo po) {
    return new PaymentIntent(po.getId(), po.getTenantId(), po.getStoreId(), po.getOrderId(),
        po.getMerchantAccountId(), po.getProvider(), po.getPaymentMethod(), po.getAmount(),
        po.getCurrencyCode(), po.getStatus(), po.getIdempotencyKey(), po.getExpiresAt(),
        po.getCreatedAt(), po.getUpdatedAt());
  }
}
