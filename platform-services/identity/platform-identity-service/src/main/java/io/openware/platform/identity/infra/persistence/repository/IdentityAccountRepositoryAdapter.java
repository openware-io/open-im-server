package io.openware.platform.identity.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import io.openware.platform.identity.infra.persistence.po.IdentityAccountPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link IdentityAccountRepository} 的持久化适配器。
 *
 * <p>审计字段（created_by/at、updated_by/at）由本层填充：{@code idt_account} 的 created_at 与 updated_at
 * 是 NOT NULL 且**无默认值**，领域模型按 standards/10 不承载记账字段，因此必须在这里补上，否则 INSERT 会失败。
 *
 * <p>未接入登录态作上下文，故 createdBy/updatedBy 统一写 0（系统写入），与建表的 DEFAULT 0 语义一致；
 * 后续接入操作人上下文时只改本层。
 */
@Repository
public class IdentityAccountRepositoryAdapter implements IdentityAccountRepository {

  private final IdentityAccountMapper mapper;

  public IdentityAccountRepositoryAdapter(IdentityAccountMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<IdentityAccount> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public List<IdentityAccount> findByAccountType(String accountType) {
    return mapper.selectList(new LambdaQueryWrapper<IdentityAccountPo>()
            .eq(IdentityAccountPo::getAccountType, accountType))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public long countByAccountType(String accountType) {
    return mapper.selectCount(new LambdaQueryWrapper<IdentityAccountPo>()
        .eq(IdentityAccountPo::getAccountType, accountType));
  }

  @Override
  public IdentityAccount save(IdentityAccount account) {
    LocalDateTime now = LocalDateTime.now();
    IdentityAccountPo po = new IdentityAccountPo();
    po.setId(account.id());
    po.setStatus(account.status());
    po.setAccountType(account.accountType());
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

  private IdentityAccount toDomain(IdentityAccountPo po) {
    if (po == null) {
      return null;
    }
    return new IdentityAccount(po.getId(), po.getStatus(), po.getAccountType());
  }
}
