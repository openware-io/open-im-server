package com.gvchat.im.conversation.application.secretchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.conversation.api.secretchat.CreateSecretChatRequest;
import com.gvchat.im.conversation.api.secretchat.SecretChatResult;
import com.gvchat.im.conversation.domain.group.repository.ConversationOutboxRepository;
import com.gvchat.im.conversation.domain.secretchat.model.SecretChat;
import com.gvchat.im.conversation.domain.secretchat.port.DeviceKeyPort;
import com.gvchat.im.conversation.domain.secretchat.repository.SecretChatRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecretChatApplicationServiceTest {
  private SecretChatRepository repository;
  private SecretChatApplicationService service;

  @BeforeEach
  void setUp() {
    repository = mock(SecretChatRepository.class);
    service = new SecretChatApplicationService(repository, mock(ConversationOutboxRepository.class),
        mock(DeviceKeyPort.class), new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void createSecretChatRejectsSelfChat() {
    CreateSecretChatRequest req = new CreateSecretChatRequest();
    req.setUserB(7L);
    assertThrows(ApiException.class, () -> service.createSecretChat(7L, req));
  }

  @Test
  void createSecretChatNormalizesPairAndComputesPeer() {
    when(repository.findBetween(7L, 9L)).thenReturn(Optional.empty());
    when(repository.save(any(SecretChat.class))).thenAnswer(inv -> {
      SecretChat ch = inv.getArgument(0);
      return SecretChat.restore(1L, ch.getUserA(), ch.getUserB(), ch.getStatus(), null, "off",
          null, null, "pending", ch.getCreatedBy(), ch.getCreatedAt(), ch.getUpdatedBy(), ch.getUpdatedAt());
    });
    CreateSecretChatRequest req = new CreateSecretChatRequest();
    req.setUserB(9L);

    SecretChatResult result = service.createSecretChat(7L, req);

    assertEquals(7L, result.userA());
    assertEquals(9L, result.userB());
    assertEquals(9L, result.peerUserId());
    assertEquals("pending", result.handshakeState());
  }

  @Test
  void createSecretChatReusesExistingChat() {
    // 已存在的 pending 会话（对方尚未握手）：再次发起应复用，而不是重复插入触发唯一键冲突。
    SecretChat existing = secretChat(1L, 7L, 9L);
    when(repository.findBetween(7L, 9L)).thenReturn(Optional.of(existing));
    when(repository.save(any(SecretChat.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateSecretChatRequest req = new CreateSecretChatRequest();
    req.setUserB(9L);
    req.setPublicKey("PUB_KEY_A");

    SecretChatResult result = service.createSecretChat(7L, req);

    assertEquals(1L, result.id());
    assertEquals(9L, result.peerUserId());
    assertEquals("pending", result.handshakeState());
  }

  @Test
  void submitHandshakeCompletesWhenBothKeysPresent() {
    java.util.concurrent.atomic.AtomicReference<SecretChat> store =
        new java.util.concurrent.atomic.AtomicReference<>(secretChat(1L, 7L, 9L));
    when(repository.findById(1L)).thenAnswer(inv -> Optional.of(store.get()));
    when(repository.save(any(SecretChat.class))).thenAnswer(inv -> {
      store.set(inv.getArgument(0));
      return store.get();
    });

    SecretChatResult afterA = service.submitHandshake(7L, 1L, "PUB_KEY_A");
    assertEquals("pending", afterA.handshakeState());

    SecretChatResult afterB = service.submitHandshake(9L, 1L, "PUB_KEY_B");
    assertEquals("ready", afterB.handshakeState());
    assertEquals("ready", afterB.status());
    assertTrue(afterB.safeCode() != null && !afterB.safeCode().isBlank());
  }

  @Test
  void setDestroyPolicyRejectsUnknownPolicy() {
    when(repository.findById(1L)).thenReturn(Optional.of(secretChat(1L, 7L, 9L)));
    assertThrows(ApiException.class, () -> service.setDestroyPolicy(7L, 1L, "invalid"));
  }

  @Test
  void nonParticipantIsRejected() {
    when(repository.findById(1L)).thenReturn(Optional.of(secretChat(1L, 7L, 9L)));
    assertThrows(ApiException.class, () -> service.getSecretChat(999L, 1L));
  }

  private SecretChat secretChat(long id, long userA, long userB) {
    LocalDateTime now = LocalDateTime.now();
    return SecretChat.restore(id, userA, userB, "handshake", null, "off", null, null, "pending",
        userA, now, userA, now);
  }
}
