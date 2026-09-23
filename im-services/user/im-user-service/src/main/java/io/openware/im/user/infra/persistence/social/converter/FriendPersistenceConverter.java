package io.openware.im.user.infra.persistence.social.converter;

import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendRequest;
import io.openware.im.user.domain.social.model.FriendRequestStatus;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.infra.persistence.social.po.FriendRelationPo;
import io.openware.im.user.infra.persistence.social.po.FriendRequestPo;

public final class FriendPersistenceConverter {
  private FriendPersistenceConverter() {
  }

  public static FriendRelation toDomain(FriendRelationPo po) {
    FriendRelation relation = new FriendRelation();
    relation.restore(po.getId(), po.getUserId(), po.getFriendId(), po.getRemark(), po.getGroupName(),
        FriendStatus.valueOf(po.getStatus().toUpperCase()), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(),
        po.getUpdatedAt());
    return relation;
  }

  public static FriendRelationPo toPo(FriendRelation relation) {
    FriendRelationPo po = new FriendRelationPo();
    po.setId(relation.getId());
    po.setUserId(relation.getUserId());
    po.setFriendId(relation.getFriendId());
    po.setRemark(relation.getRemark());
    po.setGroupName(relation.getGroupName());
    po.setStatus(relation.getStatus().name().toLowerCase());
    po.setCreatedBy(relation.getCreatedBy());
    po.setCreatedAt(relation.getCreatedAt());
    po.setUpdatedBy(relation.getUpdatedBy());
    po.setUpdatedAt(relation.getUpdatedAt());
    return po;
  }

  public static FriendRequest toDomain(FriendRequestPo po) {
    FriendRequest request = new FriendRequest();
    request.restore(po.getId(), po.getFromUserId(), po.getToUserId(), po.getMessage(),
        FriendRequestStatus.valueOf(po.getStatus().toUpperCase()), po.getCreatedBy(), po.getCreatedAt(),
        po.getUpdatedBy(), po.getUpdatedAt());
    return request;
  }

  public static FriendRequestPo toPo(FriendRequest request) {
    FriendRequestPo po = new FriendRequestPo();
    po.setId(request.getId());
    po.setFromUserId(request.getFromUserId());
    po.setToUserId(request.getToUserId());
    po.setMessage(request.getMessage());
    po.setStatus(request.getStatus().name().toLowerCase());
    po.setCreatedBy(request.getCreatedBy());
    po.setCreatedAt(request.getCreatedAt());
    po.setUpdatedBy(request.getUpdatedBy());
    po.setUpdatedAt(request.getUpdatedAt());
    return po;
  }
}
