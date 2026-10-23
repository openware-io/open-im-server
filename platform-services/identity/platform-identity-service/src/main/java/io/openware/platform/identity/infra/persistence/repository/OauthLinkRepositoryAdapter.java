package io.openware.platform.identity.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.identity.domain.account.model.OauthLink;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import io.openware.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import io.openware.platform.identity.infra.persistence.po.OauthLinkPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OauthLinkRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充（idt_oauth_link 的 created_at/updated_at 为 NOT NULL 且无默认值）；
 * 领域模型不承载记账字段。
 */
@Repository
public class OauthLinkRepositoryAdapter implements OauthLinkRepository {

  private final OauthLinkMapper mapper;

  public OauthLinkRepositoryAdapter(OauthLinkMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<OauthLink> findByProviderAccount(String provider, String appId, String providerAccountId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<OauthLinkPo>()
        .eq(OauthLinkPo::getProvider, provider)
        .eq(OauthLinkPo::getAppId, appId)
        .eq(OauthLinkPo::getProviderAccountId, providerAccountId))).map(this::toDomain);
  }

  @Override
  public List<OauthLink> findByAccountId(Long accountId) {
    return mapper.selectList(new LambdaQueryWrapper<OauthLinkPo>()
            .eq(OauthLinkPo::getAccountId, accountId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public List<OauthLink> findAllByProviderAccount(String provider, String appId, String providerAccountId) {
    return mapper.selectList(new LambdaQueryWrapper<OauthLinkPo>()
            .eq(OauthLinkPo::getProvider, provider)
            .eq(OauthLinkPo::getAppId, appId)
            .eq(OauthLinkPo::getProviderAccountId, providerAccountId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public OauthLink save(OauthLink link) {
    LocalDateTime now = LocalDateTime.now();
    OauthLinkPo po = new OauthLinkPo();
    po.setId(link.id());
    po.setAccountId(link.accountId());
    po.setProvider(link.provider());
    po.setAppId(link.appId());
    po.setProviderAccountId(link.providerAccountId());
    po.setScope(link.scope());
    po.setStatus(link.status());
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

  private OauthLink toDomain(OauthLinkPo po) {
    if (po == null) {
      return null;
    }
    return new OauthLink(po.getId(), po.getAccountId(), po.getProvider(), po.getAppId(),
        po.getProviderAccountId(), po.getScope(), po.getStatus());
  }
}
