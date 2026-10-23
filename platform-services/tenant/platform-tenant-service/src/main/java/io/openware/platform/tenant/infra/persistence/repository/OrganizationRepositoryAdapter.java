package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.iam.repository.OrganizationRepository;
import io.openware.platform.tenant.infra.persistence.mapper.OrganizationMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepositoryAdapter implements OrganizationRepository {

  private final OrganizationMapper mapper;

  public OrganizationRepositoryAdapter(OrganizationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Long> findTenantId(Long organizationId) {
    return Optional.ofNullable(mapper.selectTenantId(organizationId));
  }
}
