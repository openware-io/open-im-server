package com.gvchat.im.user.conversation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 会话服务内部地址配置（用户服务调用会话服务校验群级隐私）。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "internal.services.conversation")
public class ConversationServiceProperties {
  private String baseUrl;
}
