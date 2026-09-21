package com.gvchat.im.conversation.api.controller.internal;

import com.gvchat.im.conversation.application.group.GroupApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 群级隐私内部接口：供用户服务在「群聊来源」好友申请时校验群是否允许成员互加好友。 */
@RestController
@RequestMapping("/internal/groups")
@RequiredArgsConstructor
public class InternalGroupPrivacyController {
  private final GroupApplicationService groupApplicationService;

  @GetMapping("/{id}/member-friend-request-allowed")
  public Map<String, Boolean> memberFriendRequestAllowed(@PathVariable long id) {
    return Map.of("allowed", groupApplicationService.isMemberFriendRequestAllowed(id));
  }
}
