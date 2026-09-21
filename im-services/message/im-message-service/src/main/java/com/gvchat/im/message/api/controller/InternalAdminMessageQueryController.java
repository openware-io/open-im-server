package com.gvchat.im.message.api.controller;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import com.gvchat.im.message.api.admin.AdminMessagePageResponse;
import com.gvchat.im.message.api.admin.AdminMessageResponse;
import com.gvchat.im.message.api.admin.AdminMessageStatsResponse;
import com.gvchat.im.message.application.MessageApplicationService;
import com.gvchat.im.message.application.result.MessageResult;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/messages")
@RequiredArgsConstructor
public class InternalAdminMessageQueryController {
  private final MessageApplicationService messageApplicationService;

  @GetMapping
  public AdminMessagePageResponse list(@ModelAttribute AdminMessageQuery query) {
    var result = messageApplicationService.getAdminPage(query.page(), query.pageSize());
    return new AdminMessagePageResponse(result.items().stream().map(InternalAdminMessageQueryController::toResponse).toList(),
        result.total(), result.page(), result.pageSize());
  }

  @GetMapping("/stats")
  public AdminMessageStatsResponse stats(@RequestParam(defaultValue = "0") int days) {
    var result = messageApplicationService.getAdminStats(days);
    return new AdminMessageStatsResponse(result.messageCount(), result.newMessages(), result.dailySeries(),
        result.activeUsers());
  }

  /** 管理端删除任意消息（举报处理联动「删消息」）。 */
  @PostMapping("/{msgId}/delete")
  public Map<String, Object> deleteMessage(@PathVariable String msgId) {
    var result = messageApplicationService.adminDeleteMessage(msgId);
    return Map.of("ok", result.ok(), "msgId", result.msgId());
  }

  /** 服务端权威绝对未读总数（供 im-access-ws 推送时作为极光角标）。 */
  @GetMapping("/unread/{userId}")
  public Map<String, Object> unreadCount(@PathVariable long userId) {
    return Map.of("count", messageApplicationService.getUnreadCount(userId));
  }

  private static AdminMessageResponse toResponse(MessageResult value) {
    return new AdminMessageResponse(value.id(), value.msgId(), value.fromUserId(), value.toId(), value.chatType(),
        value.msgType(), value.content(), value.clientMsgId(), value.replyMsgId(), value.atUsers(), value.status(),
        value.createdAt() == null ? null : value.createdAt().toInstant(ZoneOffset.UTC));
  }

  public record AdminMessageQuery(String keyword, ChatType chatType, MsgType msgType, MsgStatus status, Long fromUserId,
      Integer page, Integer pageSize) {}
}
