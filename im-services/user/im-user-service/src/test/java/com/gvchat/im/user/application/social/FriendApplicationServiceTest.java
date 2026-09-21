package com.gvchat.im.user.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.im.user.application.privacysetting.PrivacySettingApplicationService;
import com.gvchat.im.user.application.social.command.HandleFriendRequestCommand;
import com.gvchat.im.user.application.social.command.SendFriendRequestCommand;
import com.gvchat.im.user.application.social.result.FriendResult;
import com.gvchat.im.user.conversation.ConversationGroupPrivacyClient;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.social.event.FriendAccepted;
import com.gvchat.im.user.domain.social.event.FriendRequested;
import com.gvchat.im.user.domain.social.model.FriendRelation;
import com.gvchat.im.user.domain.social.model.FriendRequest;
import com.gvchat.im.user.domain.social.model.FriendStatus;
import com.gvchat.im.user.domain.social.port.FriendEventOutbox;
import com.gvchat.im.user.domain.social.repository.FriendRelationRepository;
import com.gvchat.im.user.domain.social.repository.FriendRequestRepository;
import com.gvchat.im.user.media.MediaReferenceClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FriendApplicationServiceTest {
  private final FriendRelationRepository friendRelationRepository = mock(FriendRelationRepository.class);
  private final FriendRequestRepository friendRequestRepository = mock(FriendRequestRepository.class);
  private final FriendEventOutbox friendEventOutbox = mock(FriendEventOutbox.class);
  private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
  private final MediaReferenceClient mediaReferences = mock(MediaReferenceClient.class);
  private final PrivacySettingApplicationService privacySettingApplicationService =
      mock(PrivacySettingApplicationService.class);
  private final ConversationGroupPrivacyClient conversationGroupPrivacy =
      mock(ConversationGroupPrivacyClient.class);
  private final FriendApplicationService service = new FriendApplicationService(
      friendRelationRepository, friendRequestRepository, friendEventOutbox, userAccountRepository,
      mediaReferences, privacySettingApplicationService, conversationGroupPrivacy);

  @Test
  void friendListHidesPeersWhoBlockedMe() {
    long me = 1L;
    long blocker = 2L;
    long other = 3L;
    FriendRelation toBlocker = FriendRelation.create(me, blocker, LocalDateTime.now());
    FriendRelation toOther = FriendRelation.create(me, other, LocalDateTime.now());
    FriendRelation blockerToMe = FriendRelation.create(blocker, me, LocalDateTime.now());
    blockerToMe.block();
    when(friendRelationRepository.findByUserIdAndStatusOrderByGroupNameAndRemark(me, FriendStatus.NORMAL))
        .thenReturn(List.of(toBlocker, toOther));
    when(friendRelationRepository.findByFriendIdAndStatus(me, FriendStatus.BLOCKED))
        .thenReturn(List.of(blockerToMe));
    when(userAccountRepository.findByIds(anyList())).thenReturn(List.of());

    List<FriendResult> result = service.getFriendList(me);

    assertThat(result).extracting(FriendResult::friendId).containsExactly(other);
  }

  @Test
  void deleteFriendKeepsReverseBlockRelation() {
    long me = 1L;
    long friend = 2L;
    FriendRelation reverse = FriendRelation.create(friend, me, LocalDateTime.now());
    reverse.block();
    when(friendRelationRepository.findByUserIdAndFriendId(friend, me)).thenReturn(Optional.of(reverse));

    service.deleteFriend(me, friend);

    verify(friendRelationRepository).deleteByUserIdAndFriendId(me, friend);
    verify(friendRelationRepository, never()).deleteByUserIdAndFriendId(friend, me);
  }

  @Test
  void deleteFriendRemovesReverseNormalRelation() {
    long me = 1L;
    long friend = 2L;
    FriendRelation reverse = FriendRelation.create(friend, me, LocalDateTime.now());
    when(friendRelationRepository.findByUserIdAndFriendId(friend, me)).thenReturn(Optional.of(reverse));

    service.deleteFriend(me, friend);

    verify(friendRelationRepository).deleteByUserIdAndFriendId(me, friend);
    verify(friendRelationRepository).deleteByUserIdAndFriendId(friend, me);
  }

  @Test
  void sendRequestOverwritesExistingPendingAndReEmitsEvent() {
    long from = 1L;
    long to = 2L;
    FriendRequest existing = FriendRequest.create(from, to, "old", LocalDateTime.now());
    when(friendRelationRepository.findByUserIdAndFriendId(from, to)).thenReturn(Optional.empty());
    when(friendRequestRepository.findPendingByFromUserIdAndToUserId(from, to)).thenReturn(Optional.of(existing));
    when(friendRequestRepository.save(existing)).thenReturn(existing);

    service.sendRequest(from, new SendFriendRequestCommand(to, "new", null, null));

    assertThat(existing.getMessage()).isEqualTo("new");
    verify(friendRequestRepository).save(existing);
    verify(friendEventOutbox).append(any(FriendRequested.class));
  }

  @Test
  void acceptReactivatesResidualBlockedRelationAndDeletesRequest() {
    long from = 1L;
    long to = 2L;
    FriendRequest request = FriendRequest.create(from, to, "hi", LocalDateTime.now());
    request.assignId(10L);
    when(friendRequestRepository.findById(10L)).thenReturn(Optional.of(request));
    FriendRelation residual = FriendRelation.create(to, from, LocalDateTime.now());
    residual.block();
    when(friendRelationRepository.findByUserIdAndFriendId(to, from)).thenReturn(Optional.of(residual));
    when(friendRelationRepository.findByUserIdAndFriendId(from, to)).thenReturn(Optional.empty());

    service.handleRequest(to, 10L, new HandleFriendRequestCommand("accepted"));

    assertThat(residual.getStatus()).isEqualTo(FriendStatus.NORMAL);
    verify(friendEventOutbox).append(any(FriendAccepted.class));
    verify(friendRequestRepository).delete(10L);
  }
}
