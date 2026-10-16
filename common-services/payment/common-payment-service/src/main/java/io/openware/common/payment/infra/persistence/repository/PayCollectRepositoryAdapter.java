package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.collect.model.PayCollect;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.po.PayCollectPo;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link PayCollectRepository} 的持久化适配器。 */
@Repository
public class PayCollectRepositoryAdapter implements PayCollectRepository {

  private final PayCollectMapper mapper;

  public PayCollectRepositoryAdapter(PayCollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PayCollect> listConfirmedByOrderIds(Long tenantId, List<Long> orderIds) {
    return query(tenantId, orderIds, true);
  }

  @Override
  public List<PayCollect> listByOrderIds(Long tenantId, List<Long> orderIds) {
    return query(tenantId, orderIds, false);
  }

  private List<PayCollect> query(Long tenantId, List<Long> orderIds, boolean confirmedOnly) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<PayCollectPo>lambdaQuery()
            .eq(PayCollectPo::getTenantId, tenantId)
            .in(PayCollectPo::getOrderId, orderIds)
            // 只取已确认收款：INIT/HOLD/FAILED 是尝试而非收款，算进"已收金额"会让订单看起来收过钱。
            .eq(confirmedOnly, PayCollectPo::getState, PayCollect.STATE_CONFIRMED)
            .orderByAsc(PayCollectPo::getCreatedAt)
            .orderByAsc(PayCollectPo::getId))
        .stream().map(PayCollectRepositoryAdapter::toDomain).toList();
  }

  private static PayCollect toDomain(PayCollectPo po) {
    return new PayCollect(po.getId(), po.getTenantId(), po.getStoreId(), po.getOrderId(),
        po.getCollectNo(), po.getState(), po.getCurrencyCode(), po.getRequestJson(),
        po.getResponseJson(), po.getCreatedAt());
  }
}
