package io.openware.platform.admin.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.persistence.mapper.ConsAdminAccountMapper;
import io.openware.platform.admin.infra.persistence.po.ConsAdminAccountPo;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SaaS 后台管理员账号仓储实现：MyBatis-Plus 适配 {@code cons_admin_account}。
 *
 * <p>审计字段由本层填充：cons_admin_account 的 created_at / updated_at 是 NOT NULL（写入路径无默认值），
 * 领域模型按 standards/10 不承载记账字段，漏填会让 INSERT 失败。createdBy/updatedBy 走建表默认值 0。
 */
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
    public List<ConsAdminAccount> listByRole(AdminRole role, LocalDateTime fromInclusive,
                                             LocalDateTime toInclusive) {
        return mapper.selectList(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                        .eq(role != null, ConsAdminAccountPo::getRole, role == null ? null : role.name())
                        .ge(fromInclusive != null, ConsAdminAccountPo::getCreatedAt, fromInclusive)
                        .le(toInclusive != null, ConsAdminAccountPo::getCreatedAt, toInclusive)
                        .orderByAsc(ConsAdminAccountPo::getId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsByUsername(String username) {
        return mapper.selectCount(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                .eq(ConsAdminAccountPo::getUsername, username)) > 0;
    }

    @Override
    public boolean existsByPlatformAccountId(Long platformAccountId) {
        return mapper.selectCount(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                .eq(ConsAdminAccountPo::getPlatformAccountId, platformAccountId)) > 0;
    }

    @Override
    public boolean existsByPlatformAccountIdExcluding(Long platformAccountId, Long excludedAccountId) {
        return mapper.selectCount(Wrappers.<ConsAdminAccountPo>lambdaQuery()
                .eq(ConsAdminAccountPo::getPlatformAccountId, platformAccountId)
                .ne(ConsAdminAccountPo::getId, excludedAccountId)) > 0;
    }

    @Override
    public ConsAdminAccount save(ConsAdminAccount account) {
        LocalDateTime now = LocalDateTime.now();
        ConsAdminAccountPo po = toPo(account);
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            mapper.insert(po);
        } else {
            po.setUpdatedAt(now);
            mapper.updateById(po);
        }
        return toDomain(po);
    }

    @Override
    public boolean deleteById(Long accountId) {
        return mapper.deleteById(accountId) > 0;
    }

    @Override
    public int updatePassword(Long accountId, String passwordHash) {
        ConsAdminAccountPo po = new ConsAdminAccountPo();
        po.setId(accountId);
        po.setPasswordHash(passwordHash);
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.updateById(po);
    }

    private ConsAdminAccountPo toPo(ConsAdminAccount account) {
        ConsAdminAccountPo po = new ConsAdminAccountPo();
        po.setId(account.id());
        po.setUsername(account.username());
        po.setPasswordHash(account.passwordHash());
        po.setDisplayName(account.displayName());
        po.setImAccount(account.imAccount());
        po.setIdaasSubject(account.idaasSubject());
        po.setPlatformAccountId(account.platformAccountId());
        po.setRole(account.role() == null ? null : account.role().name());
        po.setStatus(account.status());
        return po;
    }

    private ConsAdminAccount toDomain(ConsAdminAccountPo po) {
        return new ConsAdminAccount(
                po.getId(),
                po.getUsername(),
                po.getPasswordHash(),
                po.getDisplayName(),
                po.getImAccount(),
                po.getIdaasSubject(),
                po.getPlatformAccountId(),
                po.getRole() == null ? null : AdminRole.valueOf(po.getRole()),
                po.getStatus());
    }
}
