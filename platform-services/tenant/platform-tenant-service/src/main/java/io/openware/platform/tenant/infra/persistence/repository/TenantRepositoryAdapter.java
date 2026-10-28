package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.tenant.model.Tenant;
import io.openware.platform.tenant.domain.tenant.repository.TenantRepository;
import io.openware.platform.tenant.infra.persistence.mapper.TenantMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）；
 * {@code createdAt} 同时是业务事实（区间筛选口径），因此回填进聚合返回给调用方。
 */
@Repository
public class TenantRepositoryAdapter implements TenantRepository {

  private final TenantMapper mapper;

  public TenantRepositoryAdapter(TenantMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Tenant> findByCode(String tenantCode) {
    return mapper.selectList(Wrappers.<TenantPo>lambdaQuery()
            .eq(TenantPo::getTenantCode, tenantCode)).stream().findFirst()
        .map(TenantRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<Tenant> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(TenantRepositoryAdapter::toDomain);
  }

  @Override
  public Tenant updateStatus(Tenant tenant) {
    TenantPo po = new TenantPo();
    po.setId(tenant.id());
    po.setStatus(tenant.status());
    po.setUpdatedAt(LocalDateTime.now());
    mapper.updateById(po);
    return toDomain(mapper.selectById(tenant.id()));
  }

  @Override
  public List<Tenant> listCreatedBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive) {
    return mapper.selectList(Wrappers.<TenantPo>lambdaQuery()
            .ge(fromInclusive != null, TenantPo::getCreatedAt, fromInclusive)
            .le(toInclusive != null, TenantPo::getCreatedAt, toInclusive)
            .orderByDesc(TenantPo::getId))
        .stream().map(TenantRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Tenant insert(Tenant tenant) {
    LocalDateTime now = LocalDateTime.now();
    TenantPo po = new TenantPo();
    po.setTenantCode(tenant.tenantCode());
    po.setName(tenant.name());
    po.setStatus(tenant.status());
    po.setDefaultLocale(tenant.defaultLocale());
    po.setDefaultTimezone(tenant.defaultTimezone());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  private static Tenant toDomain(TenantPo po) {
    return new Tenant(po.getId(), po.getTenantCode(), po.getName(), po.getStatus(),
        po.getDefaultLocale(), po.getDefaultTimezone(), po.getCreatedAt());
  }
}
