package io.openware.im.user.application.social;

import io.openware.im.user.api.authorization.PrivateMessageAuthorizationQuery;
import io.openware.im.user.api.authorization.PrivateMessageAuthorizationSnapshot;
import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendAuthorizationQueryService {
  private final FriendRelationRepository friendRelationRepository;

  @Transactional(readOnly = true)
  public PrivateMessageAuthorizationSnapshot authorize(PrivateMessageAuthorizationQuery query) {
    FriendRelation relation = friendRelationRepository.findByUserIdAndFriendId(query.userId(), query.peerUserId())
        .orElse(null);
    FriendRelation reverseRelation = friendRelationRepository.findByUserIdAndFriendId(query.peerUserId(), query.userId())
        .orElse(null);
    if (relation == null || reverseRelation == null) {
      return denied(query, "none", "FRIEND_RELATION_NOT_FOUND");
    }
    return snapshot(query, relation, reverseRelation);
  }

  private PrivateMessageAuthorizationSnapshot snapshot(PrivateMessageAuthorizationQuery query, FriendRelation relation,
      FriendRelation reverseRelation) {
    long authorizationVersion = Math.max(version(relation), version(reverseRelation));
    if (relation.getStatus() == FriendStatus.NORMAL && reverseRelation.getStatus() == FriendStatus.NORMAL) {
      return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(), "normal", authorizationVersion, true, null);
    }
    return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(), relation.getStatus().name().toLowerCase(),
        authorizationVersion, false, "FRIEND_RELATION_NOT_ALLOWED");
  }

  private PrivateMessageAuthorizationSnapshot denied(PrivateMessageAuthorizationQuery query, String relationStatus,
      String denialCode) {
    return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(), relationStatus, 0L, false, denialCode);
  }

  private long version(FriendRelation relation) {
    LocalDateTime timestamp = relation.getUpdatedAt() != null ? relation.getUpdatedAt() : relation.getCreatedAt();
    return timestamp == null ? 0L : timestamp.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
