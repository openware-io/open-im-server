package io.openware.im.message.api.controller;

import io.openware.infrastructure.security.SecurityUser;
import io.openware.im.message.api.converter.MessageApiConverter;
import io.openware.im.message.api.dto.request.DeleteForMeRequest;
import io.openware.im.message.api.dto.request.DeleteMessageRequest;
import io.openware.im.message.api.dto.request.EditMessageRequest;
import io.openware.im.message.api.dto.request.ClearPrivateChatRequest;
import io.openware.im.message.api.dto.request.ClearGroupChatRequest;
import io.openware.im.message.api.dto.request.MarkMessagesReadRequest;
import io.openware.im.message.api.dto.request.MessageHistoryRequest;
import io.openware.im.message.api.dto.request.MessageSyncRequest;
import io.openware.im.message.api.dto.request.SearchMessagesRequest;
import io.openware.im.message.api.dto.response.ConversationUnreadResponse;
import io.openware.im.message.api.dto.response.DeleteMessageResponse;
import io.openware.im.message.api.dto.response.EditMessageResponse;
import io.openware.im.message.api.dto.response.MarkMessagesReadResponse;
import io.openware.im.message.api.dto.response.MessageResponse;
import io.openware.im.message.api.dto.response.MessageSyncResponse;
import io.openware.im.message.api.dto.response.SearchMessagesResponse;
import io.openware.im.message.api.dto.response.UnreadCountResponse;
import io.openware.im.message.application.MessageApplicationService;
import io.openware.im.message.application.command.DeleteMessageCommand;
import io.openware.im.message.application.command.EditMessageCommand;
import io.openware.im.message.application.command.ClearPrivateChatCommand;
import io.openware.im.message.application.command.ClearGroupChatCommand;
import io.openware.im.message.application.command.MarkMessagesReadCommand;
import io.openware.im.message.application.query.MessageHistoryQuery;
import io.openware.im.message.application.query.MessageSyncQuery;
import io.openware.im.message.application.query.SearchMessagesQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "消息管理")
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {
  private final MessageApplicationService messageApplicationService;

  @GetMapping("/history")
  @Operation(summary = "查询消息历史")
  public List<MessageResponse> getHistory(@AuthenticationPrincipal SecurityUser user,
      @Valid @ModelAttribute MessageHistoryRequest request) {
    return messageApplicationService.getHistory(new MessageHistoryQuery(user.getId(), request.peerId(), request.chatType(),
        request.page(), request.pageSize(), request.centerMsgId(), request.beforeCount(), request.afterCount(),
        request.date()))
        .stream().map(MessageApiConverter::toResponse).toList();
  }

  @GetMapping("/history/dates")
  @Operation(summary = "会话内有消息的日期列表（聊天记录日历标记）")
  public List<String> getHistoryDates(@AuthenticationPrincipal SecurityUser user,
      @Valid @ModelAttribute MessageHistoryRequest request) {
    return messageApplicationService.getHistoryDates(new MessageHistoryQuery(user.getId(), request.peerId(),
        request.chatType(), request.page(), request.pageSize(), request.centerMsgId(), request.beforeCount(),
        request.afterCount(), request.date()));
  }

  @GetMapping("/channel/{channelId}")
  @Operation(summary = "频道消息游标拉取（订阅者增量同步）")
  public List<MessageResponse> getChannelMessages(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long channelId, @RequestParam(defaultValue = "0") long afterSeq,
      @RequestParam(defaultValue = "50") int limit) {
    return messageApplicationService.listChannelMessages(user.getId(), channelId, afterSeq, limit)
        .stream().map(MessageApiConverter::toResponse).toList();
  }

  @GetMapping("/search")
  @Operation(summary = "搜索消息")
  public SearchMessagesResponse search(@AuthenticationPrincipal SecurityUser user,
      @Valid @ModelAttribute SearchMessagesRequest request) {
    return MessageApiConverter.toResponse(messageApplicationService.search(new SearchMessagesQuery(user.getId(),
        request.keyword(), request.peerId(), request.chatType(), request.msgType(), request.page(), request.pageSize())));
  }

  @GetMapping("/sync")
  @Operation(summary = "按用户同步序号补齐消息")
  public MessageSyncResponse syncMessages(@AuthenticationPrincipal SecurityUser user,
      @Valid @ModelAttribute MessageSyncRequest request) {
    return MessageApiConverter.toResponse(messageApplicationService.sync(new MessageSyncQuery(user.getId(),
        request.afterSyncSeq(), request.limit())));
  }

  @GetMapping("/unread-count")
  @Operation(summary = "查询未读消息数")
  public UnreadCountResponse getUnreadCount(@AuthenticationPrincipal SecurityUser user) {
    return new UnreadCountResponse(messageApplicationService.getUnreadCount(user.getId()));
  }

  @GetMapping("/unread-by-conversation")
  @Operation(summary = "按会话查询未读消息数")
  public List<ConversationUnreadResponse> getUnreadByConversation(@AuthenticationPrincipal SecurityUser user) {
    return messageApplicationService.getUnreadByConversation(user.getId()).stream()
        .map(item -> new ConversationUnreadResponse(item.conversationId(), item.count())).toList();
  }

  @PostMapping("/edit")
  @Operation(summary = "编辑消息（发送后 2 分钟内仅发送者本人）")
  public EditMessageResponse editMessage(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody EditMessageRequest request) {
    return MessageApiConverter.toResponse(messageApplicationService.editMessage(
        new EditMessageCommand(user.getId(), request.msgId(), request.newContent())));
  }

  /**
   * 「删除仅我」：仅对当前用户隐藏这些消息，不影响其它成员。
   *
   * <p>墓碑持久化在服务端，卸载重装后重新同步也不会把消息“复活”。
   */
  @PostMapping("/delete-for-me")
  @Operation(summary = "删除仅我（不影响对方，跨重装保留）")
  public Map<String, Object> deleteForMe(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody DeleteForMeRequest request) {
    int marked = messageApplicationService.deleteForMe(user.getId(), request.msgIds());
    return Map.of("ok", true, "deleted", marked);
  }

  @PostMapping("/delete-for-everyone")
  @Operation(summary = "删除所有人的消息")
  public DeleteMessageResponse deleteMessageForEveryone(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody DeleteMessageRequest request) {
    return MessageApiConverter.toResponse(messageApplicationService.deleteForEveryone(
        new DeleteMessageCommand(user.getId(), request.msgId())));
  }

  @PostMapping("/recall")
  @Operation(summary = "撤回消息（限时窗口内）")
  public DeleteMessageResponse recallMessage(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody DeleteMessageRequest request) {
    return MessageApiConverter.toResponse(messageApplicationService.deleteForEveryone(
        new DeleteMessageCommand(user.getId(), request.msgId(), true)));
  }

  @PostMapping("/clear-private")
  @Operation(summary = "清空私聊聊天记录（服务端删除）")
  public Map<String, Boolean> clearPrivateChat(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody ClearPrivateChatRequest request) {
    messageApplicationService.clearPrivateChat(new ClearPrivateChatCommand(user.getId(), request.peerId()));
    return Map.of("ok", true);
  }

  @PostMapping("/clear-group")
  @Operation(summary = "清空群聊聊天记录（服务端删除）")
  public Map<String, Boolean> clearGroupChat(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody ClearGroupChatRequest request) {
    messageApplicationService.clearGroupChat(new ClearGroupChatCommand(user.getId(), request.groupId()));
    return Map.of("ok", true);
  }

  @PostMapping("/read")
  @Operation(summary = "标记消息已读")
  public MarkMessagesReadResponse markAsRead(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody MarkMessagesReadRequest request) {
    int count = messageApplicationService.markRead(new MarkMessagesReadCommand(user.getId(), request.msgIds())).count();
    return new MarkMessagesReadResponse(true, count);
  }
}
