package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.method.model.TenantPaymentMethod;
import io.openware.common.payment.domain.method.repository.TenantPaymentMethodRepository;
import io.openware.common.payment.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.common.payment.infra.persistence.po.TenantPaymentMethodPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link TenantPaymentMethodRepository} 的持久化适配器。 */
@Repository
public class TenantPaymentMethodRepositoryAdapter implements TenantPaymentMethodRepository {

  private final TenantPaymentMethodMapper mapper;

  public TenantPaymentMethodRepositoryAdapter(TenantPaymentMethodMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<TenantPaymentMethod> findByMethod(Long tenantId, String method) {
    return mapper.selectList(Wrappers.<TenantPaymentMethodPo>lambdaQuery()
            .eq(TenantPaymentMethodPo::getTenantId, tenantId)
            .eq(TenantPaymentMethodPo::getMethod, method))
        .stream().findFirst().map(TenantPaymentMethodRepositoryAdapter::toDomain);
  }

  @Override
  public List<TenantPaymentMethod> listByTenant(Long tenantId) {
    return mapper.selectList(Wrappers.<TenantPaymentMethodPo>lambdaQuery()
            .eq(TenantPaymentMethodPo::getTenantId, tenantId))
        .stream().map(TenantPaymentMethodRepositoryAdapter::toDomain).toList();
  }

  @Override
  public TenantPaymentMethod insert(TenantPaymentMethod method) {
    LocalDateTime now = LocalDateTime.now();
    TenantPaymentMethodPo po = new TenantPaymentMethodPo();
    po.setTenantId(method.tenantId());
    po.setMethod(method.method());
    po.setGranted(Boolean.TRUE.equals(method.granted()) ? 1 : 0);
    po.setUserEnabled(Boolean.TRUE.equals(method.userEnabled()) ? 1 : 0);
    po.setStatus(method.status());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateSwitches(TenantPaymentMethod method) {
    // 只写两个开关列：租户与支付方式是这条配置的身份，不参与更新。
    return mapper.update(null, Wrappers.<TenantPaymentMethodPo>lambdaUpdate()
        .eq(TenantPaymentMethodPo::getId, method.id())
        .set(TenantPaymentMethodPo::getGranted, Boolean.TRUE.equals(method.granted()) ? 1 : 0)
        .set(TenantPaymentMethodPo::getUserEnabled, Boolean.TRUE.equals(method.userEnabled()) ? 1 : 0)
        .set(TenantPaymentMethodPo::getUpdatedAt, LocalDateTime.now())) > 0;
  }

  private static TenantPaymentMethod toDomain(TenantPaymentMethodPo po) {
    return new TenantPaymentMethod(po.getId(), po.getTenantId(), po.getMethod(),
        po.getGranted() != null && po.getGranted() == 1,
        po.getUserEnabled() != null && po.getUserEnabled() == 1, po.getStatus(), po.getCreatedAt(),
        po.getUpdatedAt());
  }
}
