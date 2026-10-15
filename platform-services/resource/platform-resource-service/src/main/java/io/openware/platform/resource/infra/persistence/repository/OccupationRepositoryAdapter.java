package io.openware.platform.resource.infra.persistence.repository;

import io.openware.platform.resource.domain.occupation.model.Occupation;
import io.openware.platform.resource.domain.occupation.repository.OccupationRepository;
import io.openware.platform.resource.infra.persistence.mapper.OccupationMapper;
import io.openware.platform.resource.infra.persistence.po.OccupationPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OccupationRepository} 的持久化适配器。
 *
 * <p>本适配器基本是委托：并发语义（{@code FOR UPDATE} 行锁、乐观锁条件更新、{@code end_at} 单向收缩、
 * 跨租户扫描）都写在 mapper 的 SQL 里，属于该聚合的正确性核心。适配器只做模型映射，**不得**把这些语义
 * 搬到"先读后判再写"的 Java 代码里。
 *
 * <p>审计字段由本层填充（res_occupation 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class OccupationRepositoryAdapter implements OccupationRepository {

  private final OccupationMapper mapper;

  public OccupationRepositoryAdapter(OccupationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Long> findActiveResourceIds(Long tenantId) {
    return mapper.selectActiveResourceIds(tenantId);
  }

  @Override
  public List<Occupation> findValidForUpdate(Long tenantId, Long resourceId) {
    return mapper.selectValidForUpdate(tenantId, resourceId).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Occupation> findById(Long occupationId) {
    return Optional.ofNullable(mapper.selectById(occupationId)).map(this::toDomain);
  }

  @Override
  public Occupation insert(Occupation occupation) {
    LocalDateTime now = LocalDateTime.now();
    OccupationPo po = toPo(occupation);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean releaseWithVersion(Long occupationId, int version, LocalDateTime releasedAt) {
    // 收缩 end_at 与状态迁移在同一条 UPDATE 内完成，见端口契约。
    return mapper.releaseWithVersion(occupationId, version, releasedAt) == 1;
  }

  @Override
  public boolean cancelWithVersion(Long occupationId, int version, LocalDateTime cancelledAt) {
    return mapper.cancelWithVersion(occupationId, version, cancelledAt) == 1;
  }

  @Override
  public List<Occupation> findExpiredHolds(LocalDateTime now, int limit) {
    return mapper.selectExpiredHolds(now, limit).stream().map(this::toDomain).toList();
  }

  @Override
  public List<Occupation> findEndedOccupations(LocalDateTime now, int limit) {
    return mapper.selectEndedOccupations(now, limit).stream().map(this::toDomain).toList();
  }

  private OccupationPo toPo(Occupation occupation) {
    OccupationPo po = new OccupationPo();
    po.setId(occupation.id());
    po.setTenantId(occupation.tenantId());
    po.setStoreId(occupation.storeId());
    po.setResourceId(occupation.resourceId());
    po.setSourceType(occupation.sourceType());
    po.setSourceId(occupation.sourceId());
    po.setStartAt(occupation.startAt());
    po.setEndAt(occupation.endAt());
    po.setStatus(occupation.status());
    po.setHoldExpiresAt(occupation.holdExpiresAt());
    po.setVersion(occupation.version());
    return po;
  }

  private Occupation toDomain(OccupationPo po) {
    return new Occupation(po.getId(), po.getTenantId(), po.getStoreId(), po.getResourceId(),
        po.getSourceType(), po.getSourceId(), po.getStartAt(), po.getEndAt(), po.getStatus(),
        po.getHoldExpiresAt(), po.getVersion());
  }
}
