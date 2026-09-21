package com.gvchat.im.conversation.infra.integration.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "internal.service-auth.services.admin")
public class AdminServiceProperties {
  private String baseUrl;
}
