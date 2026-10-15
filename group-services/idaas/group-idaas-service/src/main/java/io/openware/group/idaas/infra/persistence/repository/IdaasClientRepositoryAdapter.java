package io.openware.group.idaas.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.group.idaas.domain.client.model.IdaasClient;
import io.openware.group.idaas.domain.client.repository.IdaasClientRepository;
import io.openware.group.idaas.infra.persistence.mapper.IdaasClientMapper;
import io.openware.group.idaas.infra.persistence.po.IdaasClientPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link IdaasClientRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充：idaas_client 的 created_at 与 updated_at 是 NOT NULL 且无默认值，
 * 领域模型按 standards/10 不承载记账字段，漏填会让 INSERT 失败。createdBy/updatedBy 走建表默认值 0。
 */
@Repository
public class IdaasClientRepositoryAdapter implements IdaasClientRepository {

  private final IdaasClientMapper mapper;

  public IdaasClientRepositoryAdapter(IdaasClientMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<IdaasClient> listAll() {
    return mapper.selectList(new LambdaQueryWrapper<IdaasClientPo>().orderByAsc(IdaasClientPo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<IdaasClient> findByClientId(String clientId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<IdaasClientPo>()
        .eq(IdaasClientPo::getClientId, clientId))).map(this::toDomain);
  }

  @Override
  public IdaasClient save(IdaasClient client) {
    LocalDateTime now = LocalDateTime.now();
    IdaasClientPo po = new IdaasClientPo();
    po.setId(client.id());
    po.setName(client.name());
    po.setClientId(client.clientId());
    po.setClientSecret(client.clientSecret());
    po.setRedirectUris(client.redirectUris());
    po.setStatus(client.status());
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

  private IdaasClient toDomain(IdaasClientPo po) {
    if (po == null) {
      return null;
    }
    return new IdaasClient(po.getId(), po.getName(), po.getClientId(), po.getClientSecret(),
        po.getRedirectUris(), po.getStatus());
  }
}
