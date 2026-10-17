package io.openware.im.user.infra.persistence.social.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.social.model.FriendRequest;
import io.openware.im.user.domain.social.model.FriendRequestStatus;
import io.openware.im.user.domain.social.repository.AuditSnapshot;
import io.openware.im.user.domain.social.repository.FriendRequestRepository;
import io.openware.im.user.domain.social.repository.Persisted;
import io.openware.im.user.infra.persistence.social.converter.FriendPersistenceConverter;
import io.openware.im.user.infra.persistence.social.mapper.FriendRequestMapper;
import io.openware.im.user.infra.persistence.social.po.FriendRequestPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FriendRequestRepositoryAdapter implements FriendRequestRepository {
  private final FriendRequestMapper mapper;

  @Override
  public Optional<Persisted<FriendRequest>> findPendingByFromUserIdAndToUserId(Long fromUserId, Long toUserId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<FriendRequestPo>lambdaQuery()
            .eq(FriendRequestPo::getFromUserId, fromUserId)
            .eq(FriendRequestPo::getToUserId, toUserId)
            .eq(FriendRequestPo::getStatus, FriendRequestStatus.PENDING.name().toLowerCase())))
        .map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<FriendRequest>> findById(Long id) {
    return Optional.ofNullable(mapper.selectByIdForUpdate(id)).map(this::toPersisted);
  }

  @Override
  public List<Persisted<FriendRequest>> findPendingByToUserIdOrderByCreatedAtDesc(Long userId) {
    return mapper.selectList(Wrappers.<FriendRequestPo>lambdaQuery().eq(FriendRequestPo::getToUserId, userId)
            .eq(FriendRequestPo::getStatus, FriendRequestStatus.PENDING.name().toLowerCase())
            .orderByDesc(FriendRequestPo::getCreatedAt))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Persisted<FriendRequest> save(FriendRequest request) {
    FriendRequestPo po = FriendPersistenceConverter.toPo(request);
    // 审计列由适配器确定（standards/10 L299）：申请人恒为发起方。
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedBy(po.getFromUserId());
    po.setUpdatedBy(po.getFromUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      request.assignId(po.getId());
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    mapper.updateById(po);
    return toPersisted(mapper.selectByIdForUpdate(po.getId()));
  }

  /**
   * 重新申请：把 {@code created_at} 与 {@code updated_at} 一并刷新为当前时刻。
   *
   * <p>与 {@link #save} 的区别只在申请时刻：列表按它倒序，重新申请必须排回最前。
   * 这正是领域模型不需要携带时刻的原因——时刻由写库动作决定。
   */
  @Override
  public Persisted<FriendRequest> reapply(FriendRequest request) {
    FriendRequestPo po = FriendPersistenceConverter.toPo(request);
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedBy(po.getFromUserId());
    po.setUpdatedBy(po.getFromUserId());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.updateById(po);
    return toPersisted(mapper.selectByIdForUpdate(po.getId()));
  }

  /** 业务聚合 + 记账快照：响应要回显审计列，而它们不进领域模型。 */
  private Persisted<FriendRequest> toPersisted(FriendRequestPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(FriendPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  @Override
  public void delete(Long id) {
    mapper.deleteById(id);
  }
}
