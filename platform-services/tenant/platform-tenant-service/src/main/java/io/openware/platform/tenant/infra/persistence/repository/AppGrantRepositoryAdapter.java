package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.app.repository.AppGrantRepository;
import io.openware.platform.tenant.infra.persistence.mapper.AppGrantMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class AppGrantRepositoryAdapter implements AppGrantRepository {

  private final AppGrantMapper mapper;

  public AppGrantRepositoryAdapter(AppGrantMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<String> findAuthorizedApps(Long accountId, Long tenantId) {
    return mapper.selectAuthorizedApps(accountId, tenantId);
  }
}
