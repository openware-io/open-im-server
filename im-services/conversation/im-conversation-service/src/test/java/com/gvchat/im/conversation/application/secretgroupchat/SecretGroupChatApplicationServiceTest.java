package com.gvchat.im.conversation.application.secretgroupchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.im.conversation.api.secretgroupchat.CreateSecretGroupChatRequest;
import com.gvchat.im.conversation.api.secretgroupchat.SecretGroupChatResult;
import com.gvchat.im.conversation.domain.secretchat.port.DeviceKeyPort;
import com.gvchat.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import com.gvchat.im.conversation.domain.secretgroupchat.model.SecretGroupMember;
import com.gvchat.im.conversation.domain.secretgroupchat.repository.SecretGroupChatRepository;
import com.gvchat.im.conversation.domain.secretgroupchat.repository.SecretGroupMemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecretGroupChatApplicationServiceTest {
  private SecretGroupChatRepository groupRepository;
  private SecretGroupMemberRepository memberRepository;
  private DeviceKeyPort deviceKeyPort;
  private SecretGroupChatApplicationService service;

  @BeforeEach
  void setUp() {
    groupRepository = mock(SecretGroupChatRepository.class);
    memberRepository = mock(SecretGroupMemberRepository.class);
    deviceKeyPort = mock(DeviceKeyPort.class);
    service = new SecretGroupChatApplicationService(groupRepository, memberRepository, deviceKeyPort);
  }

  @Test
  void createGroupAddsOwnerAndMembers() {
    when(groupRepository.save(any(SecretGroupChat.class))).thenAnswer(inv -> {
      SecretGroupChat g = inv.getArgument(0);
      return SecretGroupChat.restore(1L, g.getOwnerUserId(), g.getStatus(), null, null, g.getSafeCode(), g.getDestroyPolicy(), false,
          null, null, null, null, false, g.getCreatedBy(), g.getCreatedAt(), g.getUpdatedBy(), g.getUpdatedAt());
    });
    CreateSecretGroupChatRequest req = new CreateSecretGroupChatRequest();
    req.setMemberUserIds(List.of(9L, 10L));

    SecretGroupChatResult result = service.createGroup(7L, req);

    assertEquals(1L, result.id());
    assertEquals(7L, result.ownerUserId());
    assertEquals("off", result.destroyPolicy());
    verify(memberRepository, times(3)).save(any(SecretGroupMember.class));
  }

  @Test
  void createGroupRejectsSelfAsMember() {
    CreateSecretGroupChatRequest req = new CreateSecretGroupChatRequest();
    req.setMemberUserIds(List.of(7L));
    assertThrows(ApiException.class, () -> service.createGroup(7L, req));
  }

  @Test
  void submitPublicKeyComputesSafeCodeWhenAllMembersHaveKeys() {
    SecretGroupChat group = SecretGroupChat.restore(1L, 7L, "active", null, null, null, "off", false, null, null, null, null, false, 7L,
        LocalDateTime.now(), 7L, LocalDateTime.now());
    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(memberRepository.findByGroupIdAndUserId(1L, 9L)).thenReturn(
        Optional.of(SecretGroupMember.restore(2L, 1L, 9L, null, LocalDateTime.now())));
    when(memberRepository.findByGroupId(1L)).thenReturn(List.of(
        SecretGroupMember.restore(1L, 1L, 7L, "PK7", LocalDateTime.now()),
        SecretGroupMember.restore(2L, 1L, 9L, "PK9", LocalDateTime.now())));
    when(groupRepository.save(any(SecretGroupChat.class))).thenAnswer(inv -> inv.getArgument(0));

    SecretGroupChatResult result = service.submitPublicKey(9L, 1L, "PK9");

    assertNotNull(result.safeCode());
  }

  @Test
  void deleteGroupRejectsNonOwner() {
    SecretGroupChat group = SecretGroupChat.restore(1L, 7L, "active", null, null, null, "off", false, null, null, null, null, false, 7L,
        LocalDateTime.now(), 7L, LocalDateTime.now());
    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(memberRepository.findByGroupIdAndUserId(1L, 9L)).thenReturn(
        Optional.of(SecretGroupMember.restore(2L, 1L, 9L, null, LocalDateTime.now())));
    assertThrows(ApiException.class, () -> service.deleteGroup(9L, 1L));
  }

  @Test
  void participantsOfReturnsMemberIds() {
    SecretGroupChat group = SecretGroupChat.restore(1L, 7L, "active", null, null, null, "off", false, null, null, null, null, false, 7L,
        LocalDateTime.now(), 7L, LocalDateTime.now());
    when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
    when(memberRepository.findByGroupId(1L)).thenReturn(List.of(
        SecretGroupMember.restore(1L, 1L, 7L, null, LocalDateTime.now()),
        SecretGroupMember.restore(2L, 1L, 9L, null, LocalDateTime.now())));
    assertEquals(List.of(7L, 9L), service.participantsOf(1L));
  }
}
