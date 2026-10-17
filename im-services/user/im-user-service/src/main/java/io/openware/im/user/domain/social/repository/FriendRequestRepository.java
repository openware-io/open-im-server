package io.openware.im.user.domain.social.repository;

import io.openware.im.user.domain.social.model.FriendRequest;
import java.util.List;
import java.util.Optional;

public interface FriendRequestRepository {
  Optional<Persisted<FriendRequest>> findPendingByFromUserIdAndToUserId(Long fromUserId, Long toUserId);
  Optional<Persisted<FriendRequest>> findById(Long id);
  List<Persisted<FriendRequest>> findPendingByToUserIdOrderByCreatedAtDesc(Long userId);
  Persisted<FriendRequest> save(FriendRequest request);

  /**
   * 重新申请：覆盖附言并**把 {@code created_at} 刷新为当前时刻**（列表按申请时刻倒序，
   * 重新申请须排回最前）。与 {@link #save} 是**两个不同的业务动作**，
   * 因此领域模型不需要携带申请时刻（standards/10 L299）。
   */
  Persisted<FriendRequest> reapply(FriendRequest request);
  void delete(Long id);
}
