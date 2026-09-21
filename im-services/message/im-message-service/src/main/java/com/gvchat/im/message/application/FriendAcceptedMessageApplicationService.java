package com.gvchat.im.message.application;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import com.gvchat.im.message.application.command.StoreMessageCommand;
import com.gvchat.im.message.domain.message.model.Message;
import com.gvchat.im.message.domain.message.port.FriendAcceptanceProfilePort;
import com.gvchat.im.message.domain.message.repository.FriendAcceptMessageDedupRepository;
import com.gvchat.protocol.mq.event.FriendAcceptedEvent;
import com.gvchat.protocol.mq.support.ConversationIds;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 好友通过后的正式私聊消息用例。好友事件至少一次投递，因此先以 requestId 持久化占位，
 * 再复用消息域唯一权威写入链路；占位记录不会随消息删除，防止旧事件重放再次发消息。
 */
@Service
@Slf4j
public class FriendAcceptedMessageApplicationService {
  static final String REQUESTER_CONTENT = "我通过了你的朋友请求，现在我们可以开始聊天了";

  /** 同意方看到的居中系统提示（对齐微信）。 */
  static String accepterNoticeContent(FriendAcceptanceProfilePort.SenderProfile requester) {
    String name = requester == null || requester.username() == null || requester.username().isBlank()
        ? "对方" : requester.username();
    return "你已添加了" + name + "，现在可以开始聊天了";
  }


  /** 超过该时长的事件视为积压重放的历史事件，不再补发自动消息（避免旧会话被重新计为未读）。 */
  static final java.time.Duration STALE_EVENT_TOLERANCE = java.time.Duration.ofHours(1);
  private final MessageApplicationService messageApplicationService;
  private final FriendAcceptMessageDedupRepository dedupRepository;
  private final FriendAcceptanceProfilePort profilePort;
  private final com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository userDeletedMessageRepository;

  public FriendAcceptedMessageApplicationService(MessageApplicationService messageApplicationService,
      FriendAcceptMessageDedupRepository dedupRepository, FriendAcceptanceProfilePort profilePort,
      com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository userDeletedMessageRepository) {
    this.messageApplicationService = messageApplicationService;
    this.dedupRepository = dedupRepository;
    this.profilePort = profilePort;
    this.userDeletedMessageRepository = userDeletedMessageRepository;
  }

  /**
   * 处理好友通过事件。
   *
   * <p>注意：这里**刻意不加 {@code @Transactional}**。{@code MessageApplicationService.store} 本身是
   * REQUIRED 事务，会加入外层事务；私聊写入校验好友关系失败时抛出的异常会把外层事务标记为
   * rollback-only，即使此处 catch 住，提交阶段仍会抛 UnexpectedRollbackException，导致
   * 顺序消费者的队列被一条消息永久堵死（线上现象：双方都收不到好友通过提示）。
   * 去掉外层事务后，去重记账与消息写入各自独立提交，失败按 SKIPPED 收敛，队列可以继续前进。
   */
  public void handle(FriendAcceptedEvent event) {
    if (event == null || event.getRequestId() == null || event.getEventId() == null
        || event.getFromUserId() == null || event.getToUserId() == null) {
      throw new IllegalArgumentException("Invalid friend accepted event");
    }
    long requestId = event.getRequestId();
    if (dedupRepository.findByRequestId(requestId).isPresent()
        || !dedupRepository.createPending(requestId, event.getEventId(), utcNow())) {
      log.info("好友通过自动消息已处理，跳过重复事件, requestId={}, eventId={}", requestId, event.getEventId());
      return;
    }
    // 过期事件不再补发：MQ 积压重放时（如队列曾被堵死数天）历史「好友通过」事件会在今天才被消费，
    // 若照常补发，消息会带着旧时间戳落进旧会话——用户翻不到却把会话角标算成未读，
    // 线上表现为「角标显示 N 条未读但点进去没有未读消息」。仅对近期事件补发。
    java.time.Instant eventOccurredAt = event.getOccurredAt();
    if (eventOccurredAt != null
        && eventOccurredAt.isBefore(java.time.Instant.now().minus(STALE_EVENT_TOLERANCE))) {
      dedupRepository.mark(requestId, "SKIPPED", null, utcNow());
      log.info("好友通过自动消息：事件已过期，跳过补发, requestId={}, occurredAt={}", requestId, eventOccurredAt);
      return;
    }
    // 资料查询失败不应导致整条好友通过消息丢失（此前抛异常 -> 双方都收不到提示）。
    // 拿不到昵称时使用 userId 作为回退标识，保证消息一定落库并由会话资料补齐展示名。
    FriendAcceptanceProfilePort.SenderProfile accepter = safeProfile(event.getToUserId());
    FriendAcceptanceProfilePort.SenderProfile requester = safeProfile(event.getFromUserId());
    String conversationId = ConversationIds.privateConversation(event.getFromUserId(), event.getToUserId());
    var occurredAt = eventOccurredAt == null ? utcNow().toInstant(ZoneOffset.UTC) : eventOccurredAt;
    Message requesterMessage;
    Message accepterNotice;
    try {
      // 对齐微信：双方看到的**不是同一条消息**，各自只看自己该看的那条。
      // 1) 请求方（发起加好友的人，等待结果）收到同意方的一条普通消息；
      // 2) 同意方（刚点了同意）看到一条居中灰色系统提示，而不是“自己发出去的气泡”。
      // 同一会话的消息对双方都可见，因此各写一条后必须用「删除仅我」墓碑把它对另一方隐藏，
      // 否则双方会看到同一条消息（历史上正是这个现象）。
      requesterMessage = messageApplicationService.store(new StoreMessageCommand(
          "friend-accepted:requester:" + requestId, conversationId, event.getToUserId(), accepter.username(), null,
          ChatType.PRIVATE.getValue(), String.valueOf(event.getFromUserId()), MsgType.TEXT.getValue(),
          REQUESTER_CONTENT, null, "[]", occurredAt));
      accepterNotice = messageApplicationService.store(new StoreMessageCommand(
          "friend-accepted:accepter:" + requestId, conversationId, 0L, "", null,
          ChatType.PRIVATE.getValue(), String.valueOf(event.getToUserId()), MsgType.SYSTEM.getValue(),
          accepterNoticeContent(requester), null, "[]", occurredAt));
    } catch (RuntimeException ex) {
      // 典型场景：事件重放时双方已解除好友关系（私聊写入会校验好友关系并抛 Not friends）。
      // 这属于正常情况，绝不能继续抛出：该主题使用的是**顺序消费者**，一条永远失败的
      // 消息会把整个队列堵死，导致后续所有「好友通过」提示都收不到（线上现象）。
      // 这里记录原因并按 SKIPPED 收敛，让队列继续前进。
      dedupRepository.mark(requestId, "SKIPPED", null, utcNow());
      log.warn("好友通过自动消息写入被拒，按跳过收敛, requestId={}, eventId={}, conversationId={}, error={}",
          requestId, event.getEventId(), conversationId, ex.toString());
      return;
    }
    if (requesterMessage == null || accepterNotice == null) {
      dedupRepository.mark(requestId, "SKIPPED", null, utcNow());
      log.warn("好友通过自动消息被现有消息策略跳过, requestId={}, eventId={}", requestId, event.getEventId());
      return;
    }
    // 单方可见：请求方那条对同意方隐藏；同意方那条对请求方隐藏。
    // （不调用 deleteForMe，因为这里要绕过「可见性校验」——发送方本就能看到自己的消息。）
    userDeletedMessageRepository.markDeleted(event.getToUserId(), requesterMessage.getMsgId(), conversationId,
        ChatType.PRIVATE.name());
    userDeletedMessageRepository.markDeleted(event.getFromUserId(), accepterNotice.getMsgId(), conversationId,
        ChatType.PRIVATE.name());
    dedupRepository.mark(requestId, "SUCCEEDED", requesterMessage.getMsgId(), utcNow());
    log.info("好友通过自动消息已落库（仅提醒请求方）, requestId={}, eventId={}, conversationId={}",
        requestId, event.getEventId(), conversationId);
  }

  /**
   * 查询发送方展示资料；失败或缺失时回退为 userId，绝不中断自动消息写入。
   */
  private FriendAcceptanceProfilePort.SenderProfile safeProfile(long userId) {
    try {
      FriendAcceptanceProfilePort.SenderProfile profile = profilePort.find(userId);
      if (profile != null && profile.username() != null && !profile.username().isBlank()) {
        return profile;
      }
    } catch (RuntimeException ex) {
      log.warn("好友通过自动消息：发送方资料查询异常，使用回退标识, userId={}, error={}",
          userId, ex.toString());
    }
    return new FriendAcceptanceProfilePort.SenderProfile(userId, String.valueOf(userId));
  }

  private LocalDateTime utcNow() {
    return LocalDateTime.now(Clock.systemUTC());
  }
}
