package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.channel.repository.AuditSnapshot;
import io.openware.common.payment.domain.channel.repository.Persisted;
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
  public Optional<Persisted<TenantPaymentMethod>> findByMethod(Long tenantId, String method) {
    return mapper.selectList(Wrappers.<TenantPaymentMethodPo>lambdaQuery()
            .eq(TenantPaymentMethodPo::getTenantId, tenantId)
            .eq(TenantPaymentMethodPo::getMethod, method))
        .stream().findFirst().map(this::toPersisted);
  }

  @Override
  public List<Persisted<TenantPaymentMethod>> listByTenant(Long tenantId) {
    return mapper.selectList(Wrappers.<TenantPaymentMethodPo>lambdaQuery()
            .eq(TenantPaymentMethodPo::getTenantId, tenantId))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Persisted<TenantPaymentMethod> insert(TenantPaymentMethod method, LocalDateTime occurredAt) {
    TenantPaymentMethodPo po = new TenantPaymentMethodPo();
    po.setTenantId(method.tenantId());
    po.setMethod(method.method());
    po.setGranted(Boolean.TRUE.equals(method.granted()) ? 1 : 0);
    po.setUserEnabled(Boolean.TRUE.equals(method.userEnabled()) ? 1 : 0);
    po.setStatus(method.status());
    po.setCreatedAt(occurredAt);
    po.setUpdatedAt(occurredAt);
    mapper.insert(po);
    return toPersisted(po);
  }

  @Override
  public Persisted<TenantPaymentMethod> updateSwitches(TenantPaymentMethod method, LocalDateTime occurredAt) {
    // 只写两个开关列：租户与支付方式是这条配置的身份，不参与更新。
    int updated = mapper.update(null, Wrappers.<TenantPaymentMethodPo>lambdaUpdate()
        .eq(TenantPaymentMethodPo::getId, method.id())
        .set(TenantPaymentMethodPo::getGranted, Boolean.TRUE.equals(method.granted()) ? 1 : 0)
        .set(TenantPaymentMethodPo::getUserEnabled, Boolean.TRUE.equals(method.userEnabled()) ? 1 : 0)
        .set(TenantPaymentMethodPo::getUpdatedAt, occurredAt));
    return updated > 0 ? toPersisted(mapper.selectById(method.id())) : null;
  }

  private Persisted<TenantPaymentMethod> toPersisted(TenantPaymentMethodPo po) {
    return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedAt(), po.getUpdatedAt()));
  }

  private static TenantPaymentMethod toDomain(TenantPaymentMethodPo po) {
    return new TenantPaymentMethod(po.getId(), po.getTenantId(), po.getMethod(),
        po.getGranted() != null && po.getGranted() == 1,
        po.getUserEnabled() != null && po.getUserEnabled() == 1, po.getStatus());
  }
}
