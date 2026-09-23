package io.openware.im.admin.api;

import io.openware.im.admin.application.AdminManagementApplicationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理服务内部接口：供消息服务拉取已启用审核词（内部 HMAC 鉴权，见 InternalServiceAuthenticationFilter）。
 */
@RestController
@RequestMapping("/internal/admin")
@RequiredArgsConstructor
public class InternalAdminModerationController {
  private final AdminManagementApplicationService service;

  @GetMapping("/sensitive-words/enabled")
  public List<Map<String, String>> enabledSensitiveWords() {
    return service.enabledSensitiveWords().stream()
        .map(word -> Map.of("word", word.word(), "level", word.level().getValue()))
        .toList();
  }
}
