package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.entitlement.repository.EntitlementRepository;
import io.openware.platform.tenant.infra.persistence.mapper.EntitlementMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class EntitlementRepositoryAdapter implements EntitlementRepository {

  private final EntitlementMapper mapper;

  public EntitlementRepositoryAdapter(EntitlementMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Entitlement> findActive(long tenantId) {
    return mapper.selectActive(tenantId).stream()
        .map(row -> new Entitlement(row.getBusinessType(), row.getStatus(), row.getSource()))
        .toList();
  }

  @Override
  public void upsert(long tenantId, String featureCode, String status, String source) {
    mapper.upsert(tenantId, featureCode, status, source);
  }
}
