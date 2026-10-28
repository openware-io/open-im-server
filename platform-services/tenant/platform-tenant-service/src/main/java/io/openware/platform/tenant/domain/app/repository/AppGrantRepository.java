package io.openware.platform.tenant.domain.app.repository;

import java.util.List;

/** 账号在租户下已授权的应用入口。 */
public interface AppGrantRepository {

  List<String> findAuthorizedApps(Long accountId, Long tenantId);
}
