package io.openware.im.user.application.social;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.user.application.privacysetting.PrivacySettingApplicationService;
import io.openware.im.user.application.social.command.HandleFriendRequestCommand;
import io.openware.im.user.application.social.command.SendFriendRequestCommand;
import io.openware.im.user.application.social.command.UpdateFriendCommand;
import io.openware.im.user.application.social.result.FriendRequestResult;
import io.openware.im.user.application.social.result.FriendResult;
import io.openware.im.user.conversation.ConversationGroupPrivacyClient;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.privacysetting.model.UserPrivacySetting;
import io.openware.im.user.domain.social.event.FriendAccepted;
import io.openware.im.user.domain.social.event.FriendRequested;
import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendRequest;
import io.openware.im.user.domain.social.model.FriendRequestStatus;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.domain.social.repository.FriendEventOutbox;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import io.openware.im.user.domain.social.repository.Persisted;
import io.openware.im.user.domain.social.repository.FriendRequestRepository;
import io.openware.im.user.media.MediaReferenceClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendApplicationService {
  private final FriendRelationRepository friendRelationRepository;
  private final FriendRequestRepository friendRequestRepository;
  private final FriendEventOutbox friendEventOutbox;
  private final UserAccountRepository userAccountRepository;
  private final MediaReferenceClient mediaReferences;
  private final PrivacySettingApplicationService privacySettingApplicationService;
  private final ConversationGroupPrivacyClient conversationGroupPrivacy;

  @Transactional
  public FriendRequestResult sendRequest(Long fromUserId, SendFriendRequestCommand command) {
    Long toUserId = command.toUserId();
    if (fromUserId.equals(toUserId)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Cannot add self");
    }
    if (areFriends(fromUserId, toUserId)) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Already friends");
    }
    // 群聊添加隐私开关：接收方关闭「允许通过群聊添加我为好友」时，拒绝 group 来源的好友申请。
    if ("group".equalsIgnoreCase(command.source())) {
      UserPrivacySetting privacy = privacySettingApplicationService.get(toUserId);
      if (!privacy.isAllowGroupFriendRequest()) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "Recipient disabled group friend requests");
      }
      // 群级隐私：群主关闭「允许群成员互加好友」时拒绝。
      if (command.groupId() != null && !conversationGroupPrivacy.memberFriendRequestAllowed(command.groupId())) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "已禁用添加群成员为好友");
      }
    }
    // 幂等：同方向已存在 pending 申请时覆盖附言并**重置申请时刻**（走 reapply，列表按申请时刻倒序），
    // 避免重复 pending 行；每次申请都会重新发出 FriendRequested 事件，保证「每次新申请都触发提醒/红点」。
    Persisted<FriendRequest> persisted = friendRequestRepository
        .findPendingByFromUserIdAndToUserId(fromUserId, toUserId)
        .map(existing -> {
          FriendRequest request = existing.aggregate();
          request.overwrite(command.message());
          return friendRequestRepository.reapply(request);
        })
        .orElseGet(() -> friendRequestRepository.save(
            FriendRequest.create(fromUserId, toUserId, command.message())));
    FriendRequest request = persisted.aggregate();
    friendEventOutbox.append(new FriendRequested(
        request.getId(), request.getFromUserId(), request.getToUserId(), request.getMessage()));
    return toRequestResult(persisted);
  }

  @Transactional
  public FriendRequestResult handleRequest(Long userId, Long requestId, HandleFriendRequestCommand command) {
    Persisted<FriendRequest> row = friendRequestRepository.findById(requestId)
        .filter(item -> item.aggregate().getToUserId().equals(userId))
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Request not found"));
    FriendRequest request = row.aggregate();
    if (request.getStatus() != FriendRequestStatus.PENDING) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Request already handled");
    }
    FriendRequestStatus status = FriendRequestStatus.fromAction(command.action());
    request.handle(status);
    friendRequestRepository.save(request);
    if (status == FriendRequestStatus.ACCEPTED) {
      createFriendship(request.getFromUserId(), request.getToUserId());
      friendEventOutbox.append(new FriendAccepted(request.getId(), request.getFromUserId(), request.getToUserId()));
    }
    // 申请一旦处理（接受/拒绝）即失效：好友申请记录列表不再保留历史，仅 pending 用于「新的朋友」；
    // 添加成功后聊天中的系统消息作为唯一留痕（系统消息由消息域消费 FriendAccepted 事件生成）。
    friendRequestRepository.delete(request.getId());
    return toRequestResult(row);
  }

  @Transactional(readOnly = true)
  public List<FriendRequestResult> getPendingRequests(Long userId) {
    return friendRequestRepository.findPendingByToUserIdOrderByCreatedAtDesc(userId).stream().map(this::toRequestResult).toList();
  }

  @Transactional(readOnly = true)
  public List<FriendResult> getFriendList(Long userId) {
    List<Persisted<FriendRelation>> relations = friendRelationRepository
        .findByUserIdAndStatusOrderByGroupNameAndRemark(userId, FriendStatus.NORMAL);
    // 双向不可见：拉黑我的用户不应出现在我的好友列表里。
    Set<Long> blockers = friendRelationRepository.findByFriendIdAndStatus(userId, FriendStatus.BLOCKED).stream()
        .map(row -> row.aggregate().getUserId()).collect(Collectors.toSet());
    return toResults(relations.stream()
        .filter(row -> !blockers.contains(row.aggregate().getFriendId()))
        .toList());
  }

  @Transactional(readOnly = true)
  public List<String> getFriendGroups(Long userId) {
    return friendRelationRepository.findGroupNamesByUserId(userId);
  }

  @Transactional(readOnly = true)
  public List<FriendResult> getBlockedList(Long userId) {
    return toResults(friendRelationRepository
        .findByUserIdAndStatusOrderByGroupNameAndRemark(userId, FriendStatus.BLOCKED));
  }

  @Transactional
  public FriendResult updateFriend(Long userId, Long friendId, UpdateFriendCommand command) {
    FriendRelation relation = requireRelation(userId, friendId);
    relation.update(command.remark(), command.groupName());
    return toResult(friendRelationRepository.save(relation));
  }

  @Transactional
  public FriendResult blockFriend(Long userId, Long friendId) {
    FriendRelation relation = requireRelation(userId, friendId);
    relation.block();
    return toResult(friendRelationRepository.save(relation));
  }

  @Transactional
  public FriendResult unblockFriend(Long userId, Long friendId) {
    FriendRelation relation = friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .filter(row -> row.aggregate().getStatus() == FriendStatus.BLOCKED)
        .map(Persisted::aggregate)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Blocked friend not found"));
    relation.unblock();
    return toResult(friendRelationRepository.save(relation));
  }

  @Transactional
  public void deleteFriend(Long userId, Long friendId) {
    friendRelationRepository.deleteByUserIdAndFriendId(userId, friendId);
    // 反向关系仅在对方是普通好友时一并删除；对方若拉黑了我，保留其拉黑记录（不因我删除而失效）。
    friendRelationRepository.findByUserIdAndFriendId(friendId, userId)
        .filter(row -> row.aggregate().getStatus() == FriendStatus.NORMAL)
        .ifPresent(relation -> friendRelationRepository.deleteByUserIdAndFriendId(friendId, userId));
  }

  private boolean areFriends(Long userId, Long friendId) {
    return friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .map(row -> row.aggregate().getStatus() == FriendStatus.NORMAL)
        .orElse(false);
  }

  /**
   * 双向建立好友关系。采用 upsert：已存在的关系（如删除后残留的 blocked 行）恢复为 normal 并刷新时间，
   * 不存在则新建；避免残留反向行导致重复插入唯一键冲突，保证删除后重建、拉黑后再接受均可恢复。
   */
  private void createFriendship(Long userId, Long friendId) {
    LocalDateTime now = LocalDateTime.now();
    establish(userId, friendId, now);
    establish(friendId, userId, now);
  }

  private void establish(Long userId, Long friendId, LocalDateTime now) {
    friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .ifPresentOrElse(row -> {
          FriendRelation relation = row.aggregate();
          relation.reactivate();
          friendRelationRepository.save(relation);
        }, () -> friendRelationRepository.save(FriendRelation.create(userId, friendId)));
  }

  private FriendRelation requireRelation(Long userId, Long friendId) {
    return friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .map(Persisted::aggregate)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Friend not found"));
  }

  private List<FriendResult> toResults(List<Persisted<FriendRelation>> relations) {
    Map<Long, UserAccount> friendsById = userAccountRepository
        .findByIds(relations.stream().map(row -> row.aggregate().getFriendId()).distinct().toList()).stream()
        .map(row -> row.aggregate())
        .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    return relations.stream()
        .map(row -> toResult(row, friendsById.get(row.aggregate().getFriendId())))
        .toList();
  }

  private FriendResult toResult(Persisted<FriendRelation> row) {
    return toResult(row, userAccountRepository.findById(row.aggregate().getFriendId())
        .map(accountRow -> accountRow.aggregate()).orElse(null));
  }

  private FriendResult toResult(Persisted<FriendRelation> row, UserAccount friend) {
    FriendRelation relation = row.aggregate();
    return new FriendResult(
        relation.getId(), relation.getUserId(), relation.getFriendId(),
        friend == null ? null : friend.getUsername(), friend == null ? null : friend.getNickname(),
        friend == null ? null : mediaReferences.accessUrl(relation.getUserId(), friend.getAvatar(), friend.getId()),
        relation.getRemark(), relation.getGroupName(),
        relation.getStatus().name().toLowerCase(), row.audit().createdBy(), row.audit().createdAt(),
        row.audit().updatedBy(), row.audit().updatedAt());
  }

  private FriendRequestResult toRequestResult(Persisted<FriendRequest> row) {
    FriendRequest request = row.aggregate();
    return new FriendRequestResult(
        request.getId(), request.getFromUserId(), request.getToUserId(), request.getMessage(),
        request.getStatus().name().toLowerCase(), row.audit().createdBy(), row.audit().createdAt(),
        row.audit().updatedBy(), row.audit().updatedAt());
  }
}
