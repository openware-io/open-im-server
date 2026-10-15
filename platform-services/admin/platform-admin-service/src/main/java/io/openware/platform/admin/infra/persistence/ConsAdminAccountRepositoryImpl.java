package io.openware.platform.admin.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.persistence.mapper.ConsAdminAccountMapper;
import io.openware.platform.admin.infra.persistence.po.ConsAdminAccountPo;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;

/** SaaS 后台管理员账号仓储实现：MyBatis-Plus 适配 {@code cons_admin_account}。 */
@Repository
public class ConsAdminAccountRepositoryImpl implements ConsAdminAccountRepository {

    private final ConsAdminAccountMapper mapper;

    public ConsAdminAccountRepositoryImpl(ConsAdminAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ConsAdminAccount> findByUsername(String username) {
        ConsAdminAccountPo po = mapper.selectOne(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                .eq(ConsAdminAccountPo::getUsername, username));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<ConsAdminAccount> findBySubject(String idaasSubject) {
        ConsAdminAccountPo po = mapper.selectOne(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                .eq(ConsAdminAccountPo::getIdaasSubject, idaasSubject));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<ConsAdminAccount> findById(Long accountId) {
        return Optional.ofNullable(mapper.selectById(accountId)).map(this::toDomain);
    }

    @Override
    public int updatePassword(Long accountId, String passwordHash) {
        ConsAdminAccountPo po = new ConsAdminAccountPo();
        po.setId(accountId);
        po.setPasswordHash(passwordHash);
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.updateById(po);
    }

    private ConsAdminAccount toDomain(ConsAdminAccountPo po) {
        return new ConsAdminAccount(
                po.getId(),
                po.getUsername(),
                po.getPasswordHash(),
                po.getDisplayName(),
                po.getIdaasSubject(),
                po.getPlatformAccountId(),
                po.getRole() == null ? null : AdminRole.valueOf(po.getRole()),
                po.getStatus(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }
}
