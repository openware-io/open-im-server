package io.openware.platform.identity.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.identity.domain.account.model.LoginIdentity;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import io.openware.platform.identity.infra.persistence.po.LoginIdentityPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link LoginIdentityRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充（idt_login_identity 的 created_at/updated_at 为 NOT NULL 且无默认值）；
 * 领域模型不承载记账字段。
 */
@Repository
public class LoginIdentityRepositoryAdapter implements LoginIdentityRepository {

  private final LoginIdentityMapper mapper;

  public LoginIdentityRepositoryAdapter(LoginIdentityMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<LoginIdentity> findByLoginIdentifier(String loginType, String loginIdentifier) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<LoginIdentityPo>()
        .eq(LoginIdentityPo::getLoginType, loginType)
        .eq(LoginIdentityPo::getLoginIdentifier, loginIdentifier))).map(this::toDomain);
  }

  @Override
  public Optional<LoginIdentity> findActiveByLoginIdentifier(String loginType, String loginIdentifier) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<LoginIdentityPo>()
        .eq(LoginIdentityPo::getLoginType, loginType)
        .eq(LoginIdentityPo::getLoginIdentifier, loginIdentifier)
        .eq(LoginIdentityPo::getStatus, LoginIdentity.STATUS_ACTIVE))).map(this::toDomain);
  }

  @Override
  public List<LoginIdentity> findByAccountId(Long accountId) {
    return mapper.selectList(new LambdaQueryWrapper<LoginIdentityPo>()
            .eq(LoginIdentityPo::getAccountId, accountId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public LoginIdentity save(LoginIdentity identity) {
    LocalDateTime now = LocalDateTime.now();
    LoginIdentityPo po = new LoginIdentityPo();
    po.setId(identity.id());
    po.setAccountId(identity.accountId());
    po.setLoginType(identity.loginType());
    po.setLoginIdentifier(identity.loginIdentifier());
    po.setCredential(identity.credential());
    po.setStatus(identity.status());
    if (po.getId() == null) {
      po.setCreatedBy(0L);
      po.setCreatedAt(now);
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private LoginIdentity toDomain(LoginIdentityPo po) {
    if (po == null) {
      return null;
    }
    return new LoginIdentity(po.getId(), po.getAccountId(), po.getLoginType(),
        po.getLoginIdentifier(), po.getCredential(), po.getStatus());
  }
}
