package io.openware.im.message.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import io.openware.im.message.application.command.StoreMessageCommand;
import io.openware.im.message.domain.message.model.Message;
import io.openware.im.message.domain.message.port.FriendAcceptanceProfilePort;
import io.openware.im.message.domain.message.repository.FriendAcceptMessageDedupRepository;
import io.openware.protocol.mq.event.FriendAcceptedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FriendAcceptedMessageApplicationServiceTest {
  private final MessageApplicationService messageService = Mockito.mock(MessageApplicationService.class);
  private final FriendAcceptMessageDedupRepository dedup = Mockito.mock(FriendAcceptMessageDedupRepository.class);
  private final FriendAcceptanceProfilePort profiles = Mockito.mock(FriendAcceptanceProfilePort.class);
  private final io.openware.im.message.domain.message.repository.UserDeletedMessageRepository deletedMessages =
      Mockito.mock(io.openware.im.message.domain.message.repository.UserDeletedMessageRepository.class);
  private FriendAcceptedMessageApplicationService service;

  @BeforeEach
  void setUp() {
    service = new FriendAcceptedMessageApplicationService(messageService, dedup, profiles, deletedMessages);
  }

  @Test
  void createsIsolatedRequesterAndAccepterNotifications() {
    // 只提醒「该收到提醒的那一方」= 发起加好友的人。同意方刚点了同意、无需再被提醒；
    // 若两边各发一条，会话会变成“双方已经聊起来了”的假象（用户反馈的问题）。
    FriendAcceptedEvent event = event();
    Message requesterMessage = Message.create("friend-accepted:requester:42", "private:11:22", 1, 22,
        "qian001", "11", ChatType.PRIVATE, MsgType.TEXT,
        FriendAcceptedMessageApplicationService.REQUESTER_CONTENT, null, null, java.util.List.of(),
        LocalDateTime.now());
    when(dedup.findByRequestId(42L)).thenReturn(Optional.empty());
    when(dedup.createPending(any(Long.class), any(String.class), any(LocalDateTime.class))).thenReturn(true);
    when(profiles.find(22L)).thenReturn(new FriendAcceptanceProfilePort.SenderProfile(22L, "qian001"));
    Message accepterNotice = message("friend-accepted:accepter:42");
    when(profiles.find(11L)).thenReturn(new FriendAcceptanceProfilePort.SenderProfile(11L, "li001"));
    when(messageService.store(any(StoreMessageCommand.class))).thenReturn(requesterMessage, accepterNotice);

    service.handle(event);

    ArgumentCaptor<StoreMessageCommand> commands = ArgumentCaptor.forClass(StoreMessageCommand.class);
    verify(messageService, times(2)).store(commands.capture());
    StoreMessageCommand requesterCommand = commands.getAllValues().get(0);
    StoreMessageCommand accepterCommand = commands.getAllValues().get(1);
    assertEquals("friend-accepted:requester:42", requesterCommand.commandId());
    assertEquals("conv:private:11:22", requesterCommand.conversationId());
    assertEquals(22L, requesterCommand.senderId());
    assertEquals("11", requesterCommand.toId());
    assertEquals("text", requesterCommand.msgType());
    assertEquals(FriendAcceptedMessageApplicationService.REQUESTER_CONTENT, requesterCommand.content());
    assertEquals("friend-accepted:accepter:42", accepterCommand.commandId());
    assertEquals(0L, accepterCommand.senderId());
    assertEquals("22", accepterCommand.toId());
    assertEquals("system", accepterCommand.msgType());
    assertEquals("你已添加了li001，现在可以开始聊天了", accepterCommand.content());
    verify(deletedMessages).markDeleted(22L, requesterMessage.getMsgId(), "conv:private:11:22", "PRIVATE");
    verify(deletedMessages).markDeleted(11L, accepterNotice.getMsgId(), "conv:private:11:22", "PRIVATE");
    verify(dedup).mark(eq(42L), eq("SUCCEEDED"), eq("friend-accepted:requester:42"), any(LocalDateTime.class));
  }

  @Test
  void skipsAlreadyProcessedRequestWithoutWritingAnotherMessage() {
    when(dedup.findByRequestId(42L)).thenReturn(Optional.of(
        new FriendAcceptMessageDedupRepository.Record(42L, "evt-42", "SUCCEEDED", "msg-42")));

    service.handle(event());

    verify(messageService, never()).store(any());
    verify(profiles, never()).find(any(Long.class));
  }

  @Test
  void recordsSkippedWhenNormalMessagePolicyRejectsAutomaticMessage() {
    when(dedup.findByRequestId(42L)).thenReturn(Optional.empty());
    when(dedup.createPending(any(Long.class), any(String.class), any(LocalDateTime.class))).thenReturn(true);
    when(profiles.find(22L)).thenReturn(new FriendAcceptanceProfilePort.SenderProfile(22L, "qian001"));
    when(profiles.find(11L)).thenReturn(new FriendAcceptanceProfilePort.SenderProfile(11L, "li001"));
    when(messageService.store(any(StoreMessageCommand.class))).thenReturn(null);

    service.handle(event());

    verify(dedup).mark(eq(42L), eq("SKIPPED"), eq((String) null), any(LocalDateTime.class));
  }

  @Test
  void fallsBackToUserIdAndStillStoresMessagesWhenProfileLookupFails() {
    // 线上故障场景：资料查询持续失败（此前抛异常 -> MQ 无限重试 -> 双方永远收不到好友通过提示）。
    // 现在必须用回退标识照常落库，保证「加好友并通过后双方都有提示」这一用户可见行为。
    when(dedup.findByRequestId(42L)).thenReturn(Optional.empty());
    when(dedup.createPending(any(Long.class), any(String.class), any(LocalDateTime.class))).thenReturn(true);
    when(profiles.find(22L)).thenThrow(new IllegalStateException("user service unavailable"));
    when(profiles.find(11L)).thenThrow(new IllegalStateException("user service unavailable"));
    when(messageService.store(any(StoreMessageCommand.class)))
        .thenReturn(message("m-requester"), message("m-accepter"));

    service.handle(event());

    verify(messageService, times(2)).store(any(StoreMessageCommand.class));
    verify(dedup).mark(eq(42L), eq("SUCCEEDED"), any(String.class), any(LocalDateTime.class));
  }

  @Test
  void fallsBackWhenProfileReturnsNull() {
    when(dedup.findByRequestId(42L)).thenReturn(Optional.empty());
    when(dedup.createPending(any(Long.class), any(String.class), any(LocalDateTime.class))).thenReturn(true);
    when(profiles.find(22L)).thenReturn(null);
    when(profiles.find(11L)).thenReturn(new FriendAcceptanceProfilePort.SenderProfile(11L, "  "));
    when(messageService.store(any(StoreMessageCommand.class)))
        .thenReturn(message("m-requester"), message("m-accepter"));

    service.handle(event());

    verify(messageService, times(2)).store(any(StoreMessageCommand.class));
  }

  private Message message(String commandId) {
    return Message.create(commandId, "conv:private:11:22", 1, 22, "qian001", "11", ChatType.PRIVATE,
        MsgType.TEXT, FriendAcceptedMessageApplicationService.REQUESTER_CONTENT, null, null,
        java.util.List.of(), LocalDateTime.now());
  }

  /** 默认使用「刚刚发生」的事件：过期事件会被 STALE_EVENT_TOLERANCE 守卫跳过。 */
  private FriendAcceptedEvent event() {
    return eventAt(Instant.now());
  }

  private FriendAcceptedEvent eventAt(Instant occurredAt) {
    return FriendAcceptedEvent.builder().eventId("evt-42").requestId(42L).fromUserId(11L).toUserId(22L)
        .occurredAt(occurredAt).build();
  }

  @Test
  void skipsStaleEventSoHistoricalConversationsAreNotMarkedUnread() {
    // MQ 积压重放：数天前的好友通过事件今天才被消费，不应补发消息（否则旧会话角标出现假未读）。
    when(dedup.findByRequestId(42L)).thenReturn(Optional.empty());
    when(dedup.createPending(any(Long.class), any(String.class), any(LocalDateTime.class))).thenReturn(true);

    service.handle(eventAt(Instant.now().minus(java.time.Duration.ofDays(3))));

    verify(messageService, never()).store(any());
    verify(dedup).mark(eq(42L), eq("SKIPPED"), eq((String) null), any(LocalDateTime.class));
  }
}
