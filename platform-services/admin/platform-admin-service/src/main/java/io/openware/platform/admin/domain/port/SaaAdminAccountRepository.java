package io.openware.platform.admin.domain.port;

import io.openware.platform.admin.domain.model.SaaAdminAccount;

import java.util.Optional;

/** SaaS 后台管理员账号仓储端口。 */
public interface SaaAdminAccountRepository {

    Optional<SaaAdminAccount> findByUsername(String username);

    Optional<SaaAdminAccount> findBySubject(String idaasSubject);

    Optional<SaaAdminAccount> findById(Long accountId);

    int updatePassword(Long accountId, String passwordHash);
}
