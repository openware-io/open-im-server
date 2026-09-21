package com.gvchat.im.user.application.social;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.privacysetting.PrivacySettingApplicationService;
import com.gvchat.im.user.application.social.command.HandleFriendRequestCommand;
import com.gvchat.im.user.application.social.command.SendFriendRequestCommand;
import com.gvchat.im.user.application.social.command.UpdateFriendCommand;
import com.gvchat.im.user.application.social.result.FriendRequestResult;
import com.gvchat.im.user.application.social.result.FriendResult;
import com.gvchat.im.user.conversation.ConversationGroupPrivacyClient;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.privacysetting.model.UserPrivacySetting;
import com.gvchat.im.user.domain.social.event.FriendAccepted;
import com.gvchat.im.user.domain.social.event.FriendRequested;
import com.gvchat.im.user.domain.social.model.FriendRelation;
import com.gvchat.im.user.domain.social.model.FriendRequest;
import com.gvchat.im.user.domain.social.model.FriendRequestStatus;
import com.gvchat.im.user.domain.social.model.FriendStatus;
import com.gvchat.im.user.domain.social.port.FriendEventOutbox;
import com.gvchat.im.user.domain.social.repository.FriendRelationRepository;
import com.gvchat.im.user.domain.social.repository.FriendRequestRepository;
import com.gvchat.im.user.media.MediaReferenceClient;
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
    // 幂等：同方向已存在 pending 申请时覆盖附言并刷新时间，避免重复 pending 行；
    // 每次申请都会重新发出 FriendRequested 事件，保证「每次新申请都触发提醒/红点」。
    FriendRequest request = friendRequestRepository.findPendingByFromUserIdAndToUserId(fromUserId, toUserId)
        .map(existing -> {
          existing.overwrite(command.message(), LocalDateTime.now());
          return friendRequestRepository.save(existing);
        })
        .orElseGet(() -> friendRequestRepository.save(
            FriendRequest.create(fromUserId, toUserId, command.message(), LocalDateTime.now())));
    friendEventOutbox.append(new FriendRequested(
        request.getId(), request.getFromUserId(), request.getToUserId(), request.getMessage()));
    return toResult(request);
  }

  @Transactional
  public FriendRequestResult handleRequest(Long userId, Long requestId, HandleFriendRequestCommand command) {
    FriendRequest request = friendRequestRepository.findById(requestId)
        .filter(item -> item.getToUserId().equals(userId))
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Request not found"));
    if (request.getStatus() != FriendRequestStatus.PENDING) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Request already handled");
    }
    FriendRequestStatus status = FriendRequestStatus.fromAction(command.action());
    request.handle(status, LocalDateTime.now());
    friendRequestRepository.save(request);
    if (status == FriendRequestStatus.ACCEPTED) {
      createFriendship(request.getFromUserId(), request.getToUserId());
      friendEventOutbox.append(new FriendAccepted(request.getId(), request.getFromUserId(), request.getToUserId()));
    }
    // 申请一旦处理（接受/拒绝）即失效：好友申请记录列表不再保留历史，仅 pending 用于「新的朋友」；
    // 添加成功后聊天中的系统消息作为唯一留痕（系统消息由消息域消费 FriendAccepted 事件生成）。
    friendRequestRepository.delete(request.getId());
    return toResult(request);
  }

  @Transactional(readOnly = true)
  public List<FriendRequestResult> getPendingRequests(Long userId) {
    return friendRequestRepository.findPendingByToUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResult).toList();
  }

  @Transactional(readOnly = true)
  public List<FriendResult> getFriendList(Long userId) {
    List<FriendRelation> relations = friendRelationRepository
        .findByUserIdAndStatusOrderByGroupNameAndRemark(userId, FriendStatus.NORMAL);
    // 双向不可见：拉黑我的用户不应出现在我的好友列表里。
    Set<Long> blockers = friendRelationRepository.findByFriendIdAndStatus(userId, FriendStatus.BLOCKED).stream()
        .map(FriendRelation::getUserId).collect(Collectors.toSet());
    return toResults(relations.stream()
        .filter(relation -> !blockers.contains(relation.getFriendId()))
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
        .filter(item -> item.getStatus() == FriendStatus.BLOCKED)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Blocked friend not found"));
    relation.unblock();
    return toResult(friendRelationRepository.save(relation));
  }

  @Transactional
  public void deleteFriend(Long userId, Long friendId) {
    friendRelationRepository.deleteByUserIdAndFriendId(userId, friendId);
    // 反向关系仅在对方是普通好友时一并删除；对方若拉黑了我，保留其拉黑记录（不因我删除而失效）。
    friendRelationRepository.findByUserIdAndFriendId(friendId, userId)
        .filter(relation -> relation.getStatus() == FriendStatus.NORMAL)
        .ifPresent(relation -> friendRelationRepository.deleteByUserIdAndFriendId(friendId, userId));
  }

  private boolean areFriends(Long userId, Long friendId) {
    return friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .map(item -> item.getStatus() == FriendStatus.NORMAL)
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
        .ifPresentOrElse(relation -> {
          relation.reactivate(now);
          friendRelationRepository.save(relation);
        }, () -> friendRelationRepository.save(FriendRelation.create(userId, friendId, now)));
  }

  private FriendRelation requireRelation(Long userId, Long friendId) {
    return friendRelationRepository.findByUserIdAndFriendId(userId, friendId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Friend not found"));
  }

  private List<FriendResult> toResults(List<FriendRelation> relations) {
    Map<Long, UserAccount> friendsById = userAccountRepository
        .findByIds(relations.stream().map(FriendRelation::getFriendId).distinct().toList()).stream()
        .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    return relations.stream()
        .map(relation -> toResult(relation, friendsById.get(relation.getFriendId())))
        .toList();
  }

  private FriendResult toResult(FriendRelation relation) {
    return toResult(relation, userAccountRepository.findById(relation.getFriendId()).orElse(null));
  }

  private FriendResult toResult(FriendRelation relation, UserAccount friend) {
    return new FriendResult(
        relation.getId(), relation.getUserId(), relation.getFriendId(),
        friend == null ? null : friend.getUsername(), friend == null ? null : friend.getNickname(),
        friend == null ? null : mediaReferences.accessUrl(relation.getUserId(), friend.getAvatar(), friend.getId()),
        relation.getRemark(), relation.getGroupName(),
        relation.getStatus().name().toLowerCase(), relation.getCreatedBy(), relation.getCreatedAt(),
        relation.getUpdatedBy(), relation.getUpdatedAt());
  }

  private FriendRequestResult toResult(FriendRequest request) {
    return new FriendRequestResult(
        request.getId(), request.getFromUserId(), request.getToUserId(), request.getMessage(),
        request.getStatus().name().toLowerCase(), request.getCreatedBy(), request.getCreatedAt(),
        request.getUpdatedBy(), request.getUpdatedAt());
  }
}
