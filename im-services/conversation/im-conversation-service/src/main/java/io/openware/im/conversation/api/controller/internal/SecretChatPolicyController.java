package io.openware.im.conversation.api.controller.internal;

import io.openware.im.conversation.application.secretchat.SecretChatApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 私密聊天内部接口：供消息服务查询会话销毁策略与参与方，驱动定时销毁引擎与离线推送。 */
@RestController
@RequestMapping("/internal/secret-chats")
@RequiredArgsConstructor
public class SecretChatPolicyController {
  private final SecretChatApplicationService secretChatService;

  @GetMapping("/{secretChatId}/destroy-policy")
  public Map<String, String> destroyPolicy(@PathVariable long secretChatId) {
    return Map.of("destroyPolicy", secretChatService.destroyPolicyOf(secretChatId));
  }

  /** 返回会话参与方（userA/userB），消息服务据此计算私密消息的接收方用于离线推送。 */
  @GetMapping("/{secretChatId}/participants")
  public Map<String, Long> participants(@PathVariable long secretChatId) {
    return secretChatService.participantsOf(secretChatId);
  }
}
