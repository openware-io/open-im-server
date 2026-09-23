package io.openware.im.accessws.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "push.jpush")
public class JPushProperties {
  private boolean enabled;
  private String endpoint;
  private String appKey;
  private String masterSecret;
  private boolean apnsProduction = true;
  private int timeToLiveSeconds = 86400;
  private boolean includeContent;

  public boolean configured() {
    return enabled && appKey != null && !appKey.isBlank()
        && masterSecret != null && !masterSecret.isBlank();
  }
}
