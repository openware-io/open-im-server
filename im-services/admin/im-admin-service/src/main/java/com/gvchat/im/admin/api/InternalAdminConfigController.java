package com.gvchat.im.admin.api;

import com.gvchat.im.admin.application.AdminManagementApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理服务内部接口：供后端各服务拉取功能开关做服务端强制校验（内部 HMAC 鉴权，见 InternalServiceAuthenticationFilter）。
 */
@RestController
@RequestMapping("/internal/admin")
@RequiredArgsConstructor
public class InternalAdminConfigController {
  private final AdminManagementApplicationService service;

  @GetMapping("/config/feature-flags")
  public Map<String, Boolean> featureFlags() {
    // 需与各服务 FeatureTogglePort 的键全集保持一致（message/secret 等消费方依赖），
    // 缺失键会导致消费方按默认值放行（例如 secretChatEnabled 关闭不生效）。
    return Map.of(
        "privateChatEnabled", service.configurationEnabled("feature.privateChatEnabled", true),
        "groupChatEnabled", service.configurationEnabled("feature.groupChatEnabled", true),
        "channelEnabled", service.configurationEnabled("feature.channelEnabled", true),
        "chatDeleteEnabled", service.configurationEnabled("feature.chatDeleteEnabled", true),
        "secretChatEnabled", service.configurationEnabled("feature.secretChatEnabled", true),
        "secretGroupChatEnabled", service.configurationEnabled("feature.secretGroupChatEnabled", true));
  }
}
