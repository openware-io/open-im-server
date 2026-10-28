package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.tenant.domain.membership.Membership;
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import io.openware.platform.tenant.infra.persistence.mapper.MembershipMapper;
import io.openware.platform.tenant.infra.persistence.po.MembershipPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipRepositoryAdapter implements MembershipRepository {

  private final MembershipMapper mapper;

  public MembershipRepositoryAdapter(MembershipMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Membership> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public Optional<Membership> findByAccountAndTenantAndType(Long accountId, Long tenantId, String type) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<MembershipPo>()
        .eq(MembershipPo::getAccountId, accountId)
        .eq(MembershipPo::getTenantId, tenantId)
        .eq(MembershipPo::getMembershipType, type))).map(this::toDomain);
  }

  @Override
  public List<Membership> findAllByAccountId(Long accountId) {
    return mapper.selectActiveByAccountId(accountId).stream().map(this::toDomain).toList();
  }

  @Override
  public List<Membership> findByTenant(Long tenantId, String type, String status) {
    return mapper.selectList(new LambdaQueryWrapper<MembershipPo>()
            .eq(MembershipPo::getTenantId, tenantId)
            .eq(type != null && !type.isBlank(), MembershipPo::getMembershipType, type)
            .eq(status != null && !status.isBlank(), MembershipPo::getStatus, status)
            .orderByAsc(MembershipPo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Membership save(Membership membership) {
    LocalDateTime now = LocalDateTime.now();
    MembershipPo po = new MembershipPo();
    po.setId(membership.id());
    po.setAccountId(membership.accountId());
    po.setTenantId(membership.tenantId());
    po.setOrganizationId(membership.organizationId());
    po.setMembershipType(membership.membershipType());
    po.setMemberNo(membership.memberNo());
    po.setAuthorizationVersion(membership.authorizationVersion());
    po.setStatus(membership.status());
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

  private Membership toDomain(MembershipPo po) {
    return new Membership(po.getId(), po.getAccountId(), po.getTenantId(), po.getOrganizationId(),
        po.getMembershipType(), po.getMemberNo(),
        po.getAuthorizationVersion() == null ? 1 : po.getAuthorizationVersion(), po.getStatus());
  }
}
