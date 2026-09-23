package io.openware.im.message.application.secretgroupmessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand;
import io.openware.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand.RecipientCiphertext;
import io.openware.im.message.application.secretgroupmessage.result.SecretGroupMessageResult;
import io.openware.im.message.domain.message.model.MessageOutbox;
import io.openware.im.message.domain.message.repository.MessageOutboxRepository;
import io.openware.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import io.openware.im.message.domain.secretgroupmessage.repository.SecretGroupMessageDestroyedRepository;
import io.openware.im.message.domain.secretgroupmessage.repository.SecretGroupMessageRepository;
import io.openware.im.message.domain.message.port.FeatureTogglePort;
import io.openware.im.message.domain.secretgroupmessage.port.SecretGroupChatPort;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecretGroupMessageApplicationServiceTest {
  private static final class InMemorySecretUnreadProjection
      implements io.openware.im.message.domain.message.port.SecretUnreadProjectionPort {
    @Override public void record(String chatType, long conversationId, long userId, String msgId, long seq,
        LocalDateTime occurredAt) { }
    @Override public void markRead(String chatType, long conversationId, long userId, long afterSeq) { }
    @Override public void deleteByMessage(String chatType, long conversationId, String msgId) { }
    @Override public long countForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) {
      return 0;
    }
    @Override public List<io.openware.im.message.domain.message.port.SecretUnreadProjectionPort.ConversationUnread> countByConversationForUser(long userId, boolean secretChatEnabled,
        boolean secretGroupChatEnabled) { return List.of(); }
  }

  private SecretGroupMessageRepository messageRepository;
  private SecretGroupMessageDestroyedRepository destroyedRepository;
  private SecretGroupChatPort secretGroupChatPort;
  private MessageOutboxRepository outboxRepository;
  private SecretGroupMessageApplicationService service;

  @BeforeEach
  void setUp() {
    messageRepository = mock(SecretGroupMessageRepository.class);
    destroyedRepository = mock(SecretGroupMessageDestroyedRepository.class);
    secretGroupChatPort = mock(SecretGroupChatPort.class);
    when(secretGroupChatPort.findParticipants(anyLong())).thenReturn(List.of(7L, 9L, 10L));
    outboxRepository = mock(MessageOutboxRepository.class);
    service = new SecretGroupMessageApplicationService(messageRepository, destroyedRepository, secretGroupChatPort,
        outboxRepository, null, featureTogglePort(), new InMemorySecretUnreadProjection(), new ObjectMapper().findAndRegisterModules(), new io.openware.im.message.domain.message.repository.UserDeletedMessageRepository() {
      @Override public void markDeleted(long userId, String msgId, String conversationId, String chatType) { }
      @Override public int markDeleted(long userId, java.util.List<String> msgIds) { return 0; }
      @Override public java.util.List<String> findDeletedMsgIds(long userId, java.util.List<String> msgIds) { return java.util.List.of(); }
      @Override public java.util.List<DeletedMessage> findAllByUserId(long userId) { return java.util.List.of(); }
      @Override public void deleteByConversation(long userId, String conversationId) { }
    });
  }

  private static FeatureTogglePort featureTogglePort() {
    return featureTogglePort(true);
  }

  private static FeatureTogglePort featureTogglePort(boolean secretGroupChatEnabled) {
    return new FeatureTogglePort() {
      @Override public boolean isPrivateChatEnabled() { return true; }
      @Override public boolean isGroupChatEnabled() { return true; }
      @Override public boolean isSecretGroupChatEnabled() { return secretGroupChatEnabled; }
      @Override public boolean isChatDeleteEnabled() { return true; }
    };
  }

  @Test
  void postStoresPerRecipientCiphertextsAndPublishesEvent() {
    when(messageRepository.nextSeq(1L)).thenReturn(1L);
    when(messageRepository.save(any(SecretGroupMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    when(outboxRepository.save(any(MessageOutbox.class))).thenReturn(null);

    List<RecipientCiphertext> recipients = List.of(
        new RecipientCiphertext(9L, "cipher-9"),
        new RecipientCiphertext(10L, "cipher-10"));
    List<SecretGroupMessageResult> results = service.post(7L,
        new PostSecretGroupMessageCommand(1L, "sg-1", recipients, null, null));

    assertEquals(2, results.size());
    verify(messageRepository, times(2)).save(any(SecretGroupMessage.class));
    verify(outboxRepository, times(1)).save(any(MessageOutbox.class));
  }

  @Test
  void postIsRejectedWhenSecretGroupChatIsDisabled() {
    service = new SecretGroupMessageApplicationService(messageRepository, destroyedRepository, secretGroupChatPort,
        outboxRepository, null, featureTogglePort(false), new InMemorySecretUnreadProjection(), new ObjectMapper().findAndRegisterModules(), new io.openware.im.message.domain.message.repository.UserDeletedMessageRepository() {
      @Override public void markDeleted(long userId, String msgId, String conversationId, String chatType) { }
      @Override public int markDeleted(long userId, java.util.List<String> msgIds) { return 0; }
      @Override public java.util.List<String> findDeletedMsgIds(long userId, java.util.List<String> msgIds) { return java.util.List.of(); }
      @Override public java.util.List<DeletedMessage> findAllByUserId(long userId) { return java.util.List.of(); }
      @Override public void deleteByConversation(long userId, String conversationId) { }
    });

    assertThrows(ApiException.class,
        () -> service.post(7L, new PostSecretGroupMessageCommand(1L, "sg-1", List.of(), null, null)));
    verify(messageRepository, never()).save(any(SecretGroupMessage.class));
  }

  @Test
  void postSkipsSenderOwnCopy() {
    when(messageRepository.nextSeq(1L)).thenReturn(1L);
    when(messageRepository.save(any(SecretGroupMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    when(outboxRepository.save(any(MessageOutbox.class))).thenReturn(null);

    List<RecipientCiphertext> recipients = List.of(
        new RecipientCiphertext(7L, "cipher-self"),
        new RecipientCiphertext(9L, "cipher-9"));
    List<SecretGroupMessageResult> results = service.post(7L,
        new PostSecretGroupMessageCommand(1L, "sg-1", recipients, null, null));

    assertEquals(1, results.size());
    assertEquals(9L, results.get(0).recipientUserId());
    verify(messageRepository, times(1)).save(any(SecretGroupMessage.class));
  }

  @Test
  void postWithoutRecipientsIsRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.post(7L, new PostSecretGroupMessageCommand(1L, "sg-1", List.of(), null, null)));
    verify(messageRepository, never()).save(any(SecretGroupMessage.class));
  }

  @Test
  void ownerCanDeleteOthersMessage() {
    SecretGroupMessage message = SecretGroupMessage.post(1L, "sg-1", 7L, 9L, "cipher-9", 1L, LocalDateTime.now());
    when(messageRepository.findByMsgId(1L, "sg-1")).thenReturn(Optional.of(message));
    when(secretGroupChatPort.ownerOf(1L)).thenReturn(10L);
    when(messageRepository.deleteByMsgId(1L, "sg-1")).thenReturn(1);

    // Telegram 语义：群主（user=10）可删除发送方（user=7）的消息。
    service.deleteForEveryone(10L, 1L, "sg-1");

    verify(messageRepository, times(1)).deleteByMsgId(1L, "sg-1");
  }

  @Test
  void regularMemberCannotDeleteOthersMessage() {
    SecretGroupMessage message = SecretGroupMessage.post(1L, "sg-1", 7L, 9L, "cipher-9", 1L, LocalDateTime.now());
    when(messageRepository.findByMsgId(1L, "sg-1")).thenReturn(Optional.of(message));
    when(secretGroupChatPort.ownerOf(1L)).thenReturn(10L);

    // 普通成员（user=9，非发送方、非群主）无权删除他人消息。
    ApiException ex = assertThrows(ApiException.class, () -> service.deleteForEveryone(9L, 1L, "sg-1"));

    assertEquals(HttpStatusCodes.FORBIDDEN, ex.getStatus());
    verify(messageRepository, never()).deleteByMsgId(1L, "sg-1");
  }
}
