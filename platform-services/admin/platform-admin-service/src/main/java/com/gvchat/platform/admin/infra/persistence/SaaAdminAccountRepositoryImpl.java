package com.gvchat.platform.admin.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.domain.model.SaaAdminAccount;
import com.gvchat.platform.admin.domain.port.SaaAdminAccountRepository;
import com.gvchat.platform.admin.infra.persistence.mapper.SaaAdminAccountMapper;
import com.gvchat.platform.admin.infra.persistence.po.SaaAdminAccountPo;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;

/** SaaS 后台管理员账号仓储实现：MyBatis-Plus 适配 {@code saa_admin_account}。 */
@Repository
public class SaaAdminAccountRepositoryImpl implements SaaAdminAccountRepository {

    private final SaaAdminAccountMapper mapper;

    public SaaAdminAccountRepositoryImpl(SaaAdminAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SaaAdminAccount> findByUsername(String username) {
        SaaAdminAccountPo po = mapper.selectOne(Wrappers.<SaaAdminAccountPo>lambdaQuery()
                .eq(SaaAdminAccountPo::getUsername, username));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<SaaAdminAccount> findBySubject(String idaasSubject) {
        SaaAdminAccountPo po = mapper.selectOne(Wrappers.<SaaAdminAccountPo>lambdaQuery()
                .eq(SaaAdminAccountPo::getIdaasSubject, idaasSubject));
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<SaaAdminAccount> findById(Long accountId) {
        return Optional.ofNullable(mapper.selectById(accountId)).map(this::toDomain);
    }

    @Override
    public int updatePassword(Long accountId, String passwordHash) {
        SaaAdminAccountPo po = new SaaAdminAccountPo();
        po.setId(accountId);
        po.setPasswordHash(passwordHash);
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.updateById(po);
    }

    private SaaAdminAccount toDomain(SaaAdminAccountPo po) {
        return new SaaAdminAccount(
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
