package io.openware.im.user.application.social;

import io.openware.im.user.api.authorization.PrivateMessageAuthorizationQuery;
import io.openware.im.user.api.authorization.PrivateMessageAuthorizationSnapshot;
import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import io.openware.im.user.domain.social.repository.Persisted;
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
    Persisted<FriendRelation> relation = friendRelationRepository
        .findByUserIdAndFriendId(query.userId(), query.peerUserId()).orElse(null);
    Persisted<FriendRelation> reverseRelation = friendRelationRepository
        .findByUserIdAndFriendId(query.peerUserId(), query.userId()).orElse(null);
    if (relation == null || reverseRelation == null) {
      return denied(query, "none", "FRIEND_RELATION_NOT_FOUND");
    }
    return snapshot(query, relation, reverseRelation);
  }

  private PrivateMessageAuthorizationSnapshot snapshot(PrivateMessageAuthorizationQuery query,
      Persisted<FriendRelation> relation, Persisted<FriendRelation> reverseRelation) {
    long authorizationVersion = Math.max(version(relation), version(reverseRelation));
    if (relation.aggregate().getStatus() == FriendStatus.NORMAL
        && reverseRelation.aggregate().getStatus() == FriendStatus.NORMAL) {
      return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(), "normal", authorizationVersion, true, null);
    }
    return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(),
        relation.aggregate().getStatus().name().toLowerCase(),
        authorizationVersion, false, "FRIEND_RELATION_NOT_ALLOWED");
  }

  private PrivateMessageAuthorizationSnapshot denied(PrivateMessageAuthorizationQuery query, String relationStatus,
      String denialCode) {
    return new PrivateMessageAuthorizationSnapshot(query.userId(), query.peerUserId(), relationStatus, 0L, false, denialCode);
  }

  /**
   * 授权版本号：取该行 {@code updatedAt}，缺失时回退到 {@code createdAt}（epoch 毫秒）。
   *
   * <p><b>回退顺序是语义的一部分</b>：它跨服务供 im-message-service 判定私信是否可发，
   * 顺序改错会导致"已解除的关系仍被授权"或"未变更却击穿缓存"。
   * 该行为由 {@code FriendAuthorizationQueryServiceTest} 锁定，改动前先看那组测试。
   */
  private long version(Persisted<FriendRelation> relation) {
    LocalDateTime timestamp = relation.audit().updatedAt() != null
        ? relation.audit().updatedAt()
        : relation.audit().createdAt();
    return timestamp == null ? 0L : timestamp.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
