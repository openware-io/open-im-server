package io.openware.im.user.infra.persistence.social.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.user.domain.social.model.FriendRequest;
import io.openware.im.user.domain.social.model.FriendRequestStatus;
import io.openware.im.user.domain.social.repository.FriendRequestRepository;
import io.openware.im.user.infra.persistence.social.converter.FriendPersistenceConverter;
import io.openware.im.user.infra.persistence.social.mapper.FriendRequestMapper;
import io.openware.im.user.infra.persistence.social.po.FriendRequestPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FriendRequestRepositoryAdapter implements FriendRequestRepository {
  private final FriendRequestMapper mapper;

  @Override
  public Optional<FriendRequest> findPendingByFromUserIdAndToUserId(Long fromUserId, Long toUserId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<FriendRequestPo>lambdaQuery()
            .eq(FriendRequestPo::getFromUserId, fromUserId)
            .eq(FriendRequestPo::getToUserId, toUserId)
            .eq(FriendRequestPo::getStatus, FriendRequestStatus.PENDING.name().toLowerCase())))
        .map(FriendPersistenceConverter::toDomain);
  }

  @Override
  public Optional<FriendRequest> findById(Long id) {
    return Optional.ofNullable(mapper.selectByIdForUpdate(id)).map(FriendPersistenceConverter::toDomain);
  }

  @Override
  public List<FriendRequest> findPendingByToUserIdOrderByCreatedAtDesc(Long userId) {
    return mapper.selectList(Wrappers.<FriendRequestPo>lambdaQuery().eq(FriendRequestPo::getToUserId, userId)
            .eq(FriendRequestPo::getStatus, FriendRequestStatus.PENDING.name().toLowerCase())
            .orderByDesc(FriendRequestPo::getCreatedAt))
        .stream().map(FriendPersistenceConverter::toDomain).toList();
  }

  @Override
  public FriendRequest save(FriendRequest request) {
    FriendRequestPo po = FriendPersistenceConverter.toPo(request);
    if (po.getId() == null) {
      mapper.insert(po);
      request.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return request;
  }

  @Override
  public void delete(Long id) {
    mapper.deleteById(id);
  }
}
