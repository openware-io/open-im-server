package io.openware.platform.tenant.application;

import io.openware.platform.tenant.domain.app.repository.AppGrantRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** 内部账号应用授权只读查询。 */
@Service
public class AppGrantApplicationService {

  private final AppGrantRepository repository;

  public AppGrantApplicationService(AppGrantRepository repository) {
    this.repository = repository;
  }

  public List<String> authorizedApps(Long accountId, Long tenantId) {
    return repository.findAuthorizedApps(accountId, tenantId);
  }
}
