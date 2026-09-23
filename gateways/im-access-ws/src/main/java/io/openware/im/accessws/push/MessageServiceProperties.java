package io.openware.im.accessws.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "internal.services.message")
public class MessageServiceProperties {
  private String baseUrl;
}
