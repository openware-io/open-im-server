package com.gvchat.im.user.domain.social.repository;

import com.gvchat.im.user.domain.social.model.FriendRequest;
import java.util.List;
import java.util.Optional;

public interface FriendRequestRepository {
  Optional<FriendRequest> findPendingByFromUserIdAndToUserId(Long fromUserId, Long toUserId);
  Optional<FriendRequest> findById(Long id);
  List<FriendRequest> findPendingByToUserIdOrderByCreatedAtDesc(Long userId);
  FriendRequest save(FriendRequest request);
  void delete(Long id);
}
