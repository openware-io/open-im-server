package io.openware.platform.admin.domain.repository;

import io.openware.platform.admin.domain.model.ConsAdminAccount;

import java.util.Optional;

/** SaaS 后台管理员账号仓储端口。 */
public interface ConsAdminAccountRepository {

    Optional<ConsAdminAccount> findByUsername(String username);

    Optional<ConsAdminAccount> findBySubject(String idaasSubject);

    Optional<ConsAdminAccount> findById(Long accountId);

    int updatePassword(Long accountId, String passwordHash);
}
