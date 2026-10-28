package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.settlement.model.ChannelOrderSnapshot;
import io.openware.common.payment.domain.settlement.model.ChannelSettlement;
import io.openware.common.payment.domain.settlement.repository.ChannelSettlementRepository;
import io.openware.common.payment.infra.persistence.mapper.ChannelSettlementMapper;
import io.openware.common.payment.infra.persistence.po.ChannelOrderSnapshotPo;
import io.openware.common.payment.infra.persistence.po.PayChannelSettlementPo;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link ChannelSettlementRepository} 的持久化适配器。 */
@Repository
public class ChannelSettlementRepositoryAdapter implements ChannelSettlementRepository {

  private final ChannelSettlementMapper mapper;

  public ChannelSettlementRepositoryAdapter(ChannelSettlementMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<ChannelSettlement> findByTransaction(String provider, String transactionId, String kind) {
    if (provider == null || transactionId == null || kind == null) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<PayChannelSettlementPo>lambdaQuery()
            .eq(PayChannelSettlementPo::getProvider, normalize(provider))
            .eq(PayChannelSettlementPo::getTransactionId, transactionId)
            .eq(PayChannelSettlementPo::getKind, kind))
        .stream().findFirst().map(ChannelSettlementRepositoryAdapter::toDomain);
  }

  @Override
  public ChannelSettlement insert(ChannelSettlement settlement) {
    LocalDateTime now = LocalDateTime.now();
    PayChannelSettlementPo po = new PayChannelSettlementPo();
    po.setTenantId(settlement.tenantId());
    po.setStoreId(settlement.storeId());
    po.setOrderId(settlement.orderId());
    po.setProvider(normalize(settlement.provider()));
    po.setTransactionId(settlement.transactionId());
    po.setEventId(settlement.eventId());
    po.setKind(settlement.kind());
    po.setAmount(settlement.amount());
    po.setCurrencyCode(settlement.currencyCode());
    po.setStatus(settlement.status());
    po.setPayIntentId(settlement.payIntentId());
    po.setPayTransactionId(settlement.payTransactionId());
    // 审计字段非空且无默认值，必须由写入方提供（操作人列留默认 0）。
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public Optional<ChannelOrderSnapshot> findOrderSnapshot(Long orderId) {
    if (orderId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectOrderSnapshotById(orderId))
        .map(ChannelSettlementRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<ChannelOrderSnapshot> findOrderSnapshot(Long tenantId, Long orderId) {
    if (tenantId == null || orderId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectOrderSnapshot(tenantId, orderId))
        .map(ChannelSettlementRepositoryAdapter::toDomain);
  }

  private static String normalize(String provider) {
    return provider == null ? null : provider.trim().toUpperCase(Locale.ROOT);
  }

  private static ChannelSettlement toDomain(PayChannelSettlementPo po) {
    return new ChannelSettlement(po.getId(), po.getTenantId(), po.getStoreId(), po.getOrderId(),
        po.getProvider(), po.getTransactionId(), po.getEventId(), po.getKind(), po.getAmount(),
        po.getCurrencyCode(), po.getStatus(), po.getPayIntentId(), po.getPayTransactionId());
  }

  private static ChannelOrderSnapshot toDomain(ChannelOrderSnapshotPo po) {
    return new ChannelOrderSnapshot(po.getOrderId(), po.getTenantId(), po.getStoreId(), po.getTradeStatus(),
        po.getTotalAmount(), po.getPaidAmount(), po.getRefundableAmount(), po.getRefundStatus(),
        po.getCurrencyCode());
  }
}
