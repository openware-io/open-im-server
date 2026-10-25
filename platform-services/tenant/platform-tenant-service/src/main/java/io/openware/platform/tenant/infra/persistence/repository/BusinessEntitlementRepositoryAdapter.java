package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.tenant.domain.entitlement.repository.BusinessEntitlementRepository;
import io.openware.platform.tenant.infra.persistence.mapper.BusinessEntitlementMapper;
import io.openware.platform.tenant.infra.persistence.po.BusinessEntitlementPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessEntitlementRepositoryAdapter implements BusinessEntitlementRepository {

  private final BusinessEntitlementMapper mapper;

  public BusinessEntitlementRepositoryAdapter(BusinessEntitlementMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<BusinessEntitlement> findActive(long tenantId) {
    return mapper.selectList(new LambdaQueryWrapper<BusinessEntitlementPo>()
            .eq(BusinessEntitlementPo::getTenantId, tenantId)
            .eq(BusinessEntitlementPo::getStatus, "ACTIVE")
            .orderByAsc(BusinessEntitlementPo::getBusinessType))
        .stream().map(BusinessEntitlementRepositoryAdapter::toView).toList();
  }

  @Override
  public BusinessEntitlement find(long tenantId, String businessType) {
    BusinessEntitlementPo po = mapper.selectOne(new LambdaQueryWrapper<BusinessEntitlementPo>()
        .eq(BusinessEntitlementPo::getTenantId, tenantId)
        .eq(BusinessEntitlementPo::getBusinessType, businessType)
        .last("LIMIT 1"));
    return po == null ? null : toView(po);
  }

  @Override
  public void activate(long tenantId, String businessType, long actorId) {
    BusinessEntitlementPo existing = mapper.selectOne(new LambdaQueryWrapper<BusinessEntitlementPo>()
        .eq(BusinessEntitlementPo::getTenantId, tenantId)
        .eq(BusinessEntitlementPo::getBusinessType, businessType)
        .last("LIMIT 1"));
    LocalDateTime now = LocalDateTime.now();
    if (existing == null) {
      BusinessEntitlementPo created = new BusinessEntitlementPo();
      created.setTenantId(tenantId);
      created.setBusinessType(businessType);
      created.setStatus("ACTIVE");
      created.setSource("MANUAL");
      created.setEffectiveFrom(now);
      created.setCreatedBy(actorId);
      created.setCreatedAt(now);
      created.setUpdatedBy(actorId);
      created.setUpdatedAt(now);
      mapper.insert(created);
      return;
    }
    existing.setStatus("ACTIVE");
    existing.setSource("MANUAL");
    existing.setEffectiveFrom(now);
    existing.setUpdatedBy(actorId);
    existing.setUpdatedAt(now);
    mapper.updateById(existing);
  }

  private static BusinessEntitlement toView(BusinessEntitlementPo po) {
    return new BusinessEntitlement(po.getId(), po.getBusinessType(), po.getStatus(), po.getSource());
  }
}
