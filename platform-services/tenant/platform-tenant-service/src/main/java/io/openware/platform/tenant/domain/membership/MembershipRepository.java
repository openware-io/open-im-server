package io.openware.platform.tenant.domain.membership;

import java.util.List;
import java.util.Optional;

/** 租户成员仓储端口。 */
public interface MembershipRepository {

  Optional<Membership> findById(Long id);

  Optional<Membership> findByAccountAndTenantAndType(Long accountId, Long tenantId, String type);

  /** 登录期跨租户查询：必须显式 accountId，不走租户上下文。 */
  List<Membership> findAllByAccountId(Long accountId);

  List<Membership> findByTenant(Long tenantId, String type, String status);

  Membership save(Membership membership);
}
