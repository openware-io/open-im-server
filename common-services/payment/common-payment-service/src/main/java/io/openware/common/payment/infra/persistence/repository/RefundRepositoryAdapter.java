package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.refund.model.Refund;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.common.payment.infra.persistence.mapper.RefundMapper;
import io.openware.common.payment.infra.persistence.po.RefundPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link RefundRepository} 的持久化适配器。
 *
 * <p>{@code updateStatusGuarded} 是状态守卫的条件更新：本表没有版本列，
 * 而审批是 PENDING → APPROVED/REJECTED 的状态机，守卫条件只能是状态本身。
 */
@Repository
public class RefundRepositoryAdapter implements RefundRepository {

  private final RefundMapper mapper;

  public RefundRepositoryAdapter(RefundMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Refund> findById(Long refundId) {
    return Optional.ofNullable(mapper.selectById(refundId)).map(RefundRepositoryAdapter::toDomain);
  }

  @Override
  public List<Refund> listByCreatedAtBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive) {
    return mapper.selectList(Wrappers.<RefundPo>lambdaQuery()
            .ge(fromInclusive != null, RefundPo::getCreatedAt, fromInclusive)
            .le(toInclusive != null, RefundPo::getCreatedAt, toInclusive)
            .orderByDesc(RefundPo::getCreatedAt))
        .stream().map(RefundRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<Refund> listByOrderIds(Long tenantId, List<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<RefundPo>lambdaQuery()
            .eq(RefundPo::getTenantId, tenantId)
            .in(RefundPo::getOrderId, orderIds)
            .orderByAsc(RefundPo::getCreatedAt)
            .orderByAsc(RefundPo::getId))
        .stream().map(RefundRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Refund insert(Refund refund) {
    LocalDateTime now = LocalDateTime.now();
    RefundPo po = toPo(refund);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateStatusGuarded(Refund refund, String expectedStatus) {
    // 状态守卫：判定与写入在同一条语句里，由数据库保证并发下只有一个赢家。
    // 影响 0 行 = 状态已被他人改变（例如另一名审批者已批准），调用方必须报冲突而不是当作成功。
    return mapper.update(null, Wrappers.<RefundPo>lambdaUpdate()
        .eq(RefundPo::getId, refund.id())
        .eq(RefundPo::getStatus, expectedStatus)
        .set(RefundPo::getStatus, refund.status())
        .set(RefundPo::getApprovedAmount, refund.approvedAmount())
        .set(RefundPo::getApprovedBy, refund.approvedBy())
        .set(RefundPo::getProviderRefundNo, refund.providerRefundNo())
        .set(RefundPo::getUpdatedAt, LocalDateTime.now())) > 0;
  }

  private static RefundPo toPo(Refund refund) {
    RefundPo po = new RefundPo();
    po.setId(refund.id());
    po.setTenantId(refund.tenantId());
    po.setStoreId(refund.storeId());
    po.setBusinessType(refund.businessType());
    po.setOrderId(refund.orderId());
    po.setPaymentTransactionId(refund.paymentTransactionId());
    po.setRequestId(refund.requestId());
    po.setRequestedAmount(refund.requestedAmount());
    po.setApprovedAmount(refund.approvedAmount());
    po.setCurrencyCode(refund.currencyCode());
    po.setProviderRefundNo(refund.providerRefundNo());
    po.setStatus(refund.status());
    po.setReason(refund.reason());
    po.setRequestedBy(refund.requestedBy());
    po.setApprovedBy(refund.approvedBy());
    return po;
  }

  private static Refund toDomain(RefundPo po) {
    return new Refund(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
        po.getOrderId(), po.getPaymentTransactionId(), po.getRequestId(), po.getRequestedAmount(),
        po.getApprovedAmount(), po.getCurrencyCode(), po.getProviderRefundNo(), po.getStatus(),
        po.getReason(), po.getRequestedBy(), po.getApprovedBy(), po.getCreatedAt(), po.getUpdatedAt());
  }
}
