package io.openware.group.idaas.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.group.idaas.domain.auth.model.IdaasAccount;
import io.openware.group.idaas.domain.auth.repository.IdaasAccountRepository;
import io.openware.group.idaas.infra.persistence.mapper.IdaasAccountMapper;
import io.openware.group.idaas.infra.persistence.po.IdaasAccountPo;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link IdaasAccountRepository} 的持久化适配器。
 *
 * <p>本端口目前只有只读用法，因此未提供 save：新增账号由独立的开通流程负责，不在此臆造写方法。
 */
@Repository
public class IdaasAccountRepositoryAdapter implements IdaasAccountRepository {

  private final IdaasAccountMapper mapper;

  public IdaasAccountRepositoryAdapter(IdaasAccountMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<IdaasAccount> findByUsername(String username) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<IdaasAccountPo>()
        .eq(IdaasAccountPo::getUsername, username))).map(this::toDomain);
  }

  private IdaasAccount toDomain(IdaasAccountPo po) {
    if (po == null) {
      return null;
    }
    return new IdaasAccount(po.getId(), po.getUsername(), po.getPassword(), po.getDisplayName(),
        po.getStatus());
  }
}
