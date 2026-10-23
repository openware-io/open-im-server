package io.openware.platform.tenant.domain.iam.repository;

import java.util.Optional;

/** 组织归属只读端口：校验组织属于当前租户。 */
public interface OrganizationRepository {
  Optional<Long> findTenantId(Long organizationId);
}
