package io.openware.im.conversation.infra.integration.user;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "internal.service-auth.services.user")
public class UserServiceProperties {
  private String baseUrl;
}
