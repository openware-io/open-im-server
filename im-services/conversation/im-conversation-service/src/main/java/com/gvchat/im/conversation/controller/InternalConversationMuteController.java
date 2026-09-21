package com.gvchat.im.conversation.controller;

import com.gvchat.im.conversation.application.mute.ConversationMuteApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部免打扰查询：供 access-ws 离线推送前判断会话是否被免打扰。 */
@RestController
@RequestMapping("/internal/admin/conversations")
@RequiredArgsConstructor
public class InternalConversationMuteController {
  private final ConversationMuteApplicationService conversationMuteApplicationService;

  @GetMapping("/muted")
  public Map<String, Object> isMuted(@RequestParam long userId, @RequestParam String conversationId) {
    return Map.of("muted", conversationMuteApplicationService.isMuted(userId, conversationId));
  }
}
