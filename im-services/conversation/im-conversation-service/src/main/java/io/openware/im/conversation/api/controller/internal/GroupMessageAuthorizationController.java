package io.openware.im.conversation.api.controller.internal;

import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationQuery;
import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationSnapshot;
import io.openware.im.conversation.api.authorization.GroupMessageRecipientSnapshot;
import io.openware.im.conversation.api.authorization.ConversationMemberIdsResponse;
import io.openware.im.conversation.application.group.GroupAuthorizationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/internal/conversation/authorizations")
@RequiredArgsConstructor
public class GroupMessageAuthorizationController {
  private final GroupAuthorizationQueryService authorizationQueryService;

  @PostMapping("/group-message")
  public GroupMessageAuthorizationSnapshot authorize(@RequestBody GroupMessageAuthorizationQuery query) {
    return authorizationQueryService.authorize(query);
  }

  @PostMapping("/group-message/recipient-snapshot")
  public GroupMessageRecipientSnapshot recipientSnapshot(@RequestBody GroupMessageAuthorizationQuery query) {
    return authorizationQueryService.messageRecipientSnapshot(query);
  }

  /** 仅成员校验（忽略群状态），供聊天记录等只读路径使用。 */
  @GetMapping("/{conversationId}/members/{userId}")
  public java.util.Map<String, Object> isMember(
      @PathVariable long conversationId,
      @PathVariable long userId) {
    return java.util.Map.of("member", authorizationQueryService.isMember(conversationId, userId));
  }

  @GetMapping("/{conversationId}/member-ids")
  public ConversationMemberIdsResponse memberIds(@PathVariable long conversationId) {
    return authorizationQueryService.memberIds(conversationId);
  }
}
