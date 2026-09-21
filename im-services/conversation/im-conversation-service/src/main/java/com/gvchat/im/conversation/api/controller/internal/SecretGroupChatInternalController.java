package com.gvchat.im.conversation.api.controller.internal;

import com.gvchat.im.conversation.application.secretgroupchat.SecretGroupChatApplicationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部只读接口：供消息服务查询私密群聊销毁策略与成员（走内部服务鉴权）。 */
@RestController
@RequestMapping("/internal/secret-group-chats")
@RequiredArgsConstructor
public class SecretGroupChatInternalController {
  private final SecretGroupChatApplicationService service;

  @GetMapping("/{groupId}/destroy-policy")
  public Map<String, String> destroyPolicy(@PathVariable long groupId) {
    return Map.of("destroyPolicy", service.destroyPolicyOf(groupId));
  }

  @GetMapping("/{groupId}/participants")
  public Map<String, List<Long>> participants(@PathVariable long groupId) {
    return Map.of("userIds", service.participantsOf(groupId));
  }

  @GetMapping("/{groupId}/owner-only-post")
  public Map<String, Boolean> ownerOnlyPost(@PathVariable long groupId) {
    return Map.of("ownerOnlyPost", service.ownerOnlyPostOf(groupId));
  }

  @GetMapping("/{groupId}/owner")
  public Map<String, Long> owner(@PathVariable long groupId) {
    return Map.of("ownerUserId", service.ownerOf(groupId));
  }
}
