package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.approval.IamApproval;
import io.openware.platform.tenant.domain.approval.IamApprovalStatus;
import io.openware.platform.tenant.domain.approval.repository.IamApprovalRepository;
import io.openware.platform.tenant.infra.persistence.mapper.IamApprovalMapper;
import io.openware.platform.tenant.infra.persistence.po.IamApprovalPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link IamApprovalRepository} 的持久化适配器。
 *
 * <p>{@link IamApproval} 是可变聚合（带 {@code restore} 与状态迁移方法），因此本层用
 * {@code Applier} 回填而非构造器；PO 与聚合之间的字段对应关系只在这里出现。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值），
 * 但 {@code reviewedAt} 是**业务时间**（复核发生时刻），由聚合携带并原样落库。
 */
@Repository
public class IamApprovalRepositoryAdapter implements IamApprovalRepository {

  private final IamApprovalMapper mapper;

  public IamApprovalRepositoryAdapter(IamApprovalMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<IamApproval> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<IamApprovalPo>lambdaQuery()
        .eq(IamApprovalPo::getIdempotencyKey, idempotencyKey))).map(IamApprovalRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<IamApproval> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(IamApprovalRepositoryAdapter::toDomain);
  }

  @Override
  public IamApproval save(IamApproval approval) {
    LocalDateTime now = LocalDateTime.now();
    IamApprovalPo po = toPo(approval);
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
  public List<IamApproval> list(String actionType, String status) {
    return mapper.selectList(Wrappers.<IamApprovalPo>lambdaQuery()
            .eq(actionType != null && !actionType.isBlank(), IamApprovalPo::getActionType, actionType)
            .eq(status != null && !status.isBlank(), IamApprovalPo::getStatus, status)
            .orderByDesc(IamApprovalPo::getCreatedAt))
        .stream().map(IamApprovalRepositoryAdapter::toDomain).toList();
  }

  private static IamApproval toDomain(IamApprovalPo po) {
    IamApproval approval = new IamApproval();
    approval.restore(po.getId(), po.getTenantId(), po.getActionType(), po.getResourceType(), po.getResourceId(),
        po.getOperatorId(), po.getDetailJson(), IamApprovalStatus.valueOf(po.getStatus()),
        po.getIdempotencyKey(), po.getApproverId(), po.getReviewComment(), po.getCreatedAt(),
        po.getUpdatedAt(), po.getReviewedAt());
    return approval;
  }

  private static IamApprovalPo toPo(IamApproval approval) {
    IamApprovalPo po = new IamApprovalPo();
    po.setId(approval.getId());
    po.setTenantId(approval.getTenantId());
    po.setActionType(approval.getActionType());
    po.setResourceType(approval.getResourceType());
    po.setResourceId(approval.getResourceId());
    po.setOperatorId(approval.getOperatorId());
    po.setDetailJson(approval.getDetailJson());
    po.setStatus(approval.getStatus().name());
    po.setIdempotencyKey(approval.getIdempotencyKey());
    po.setApproverId(approval.getApproverId());
    po.setReviewComment(approval.getReviewComment());
    po.setCreatedAt(approval.getCreatedAt());
    po.setUpdatedAt(approval.getUpdatedAt());
    po.setReviewedAt(approval.getReviewedAt());
    return po;
  }
}
