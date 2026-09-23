package io.openware.im.accessws.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "im.access-ws")
public class WebSocketSecurityProperties {
  private String allowedOrigins;
  private int maxTextMessageBytes;

  public String getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(String allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  public int getMaxTextMessageBytes() {
    return maxTextMessageBytes;
  }

  public void setMaxTextMessageBytes(int maxTextMessageBytes) {
    this.maxTextMessageBytes = maxTextMessageBytes;
  }

  public String[] allowedOriginsArray() {
    return Arrays.stream(allowedOrigins.split(","))
        .map(String::trim)
        .toArray(String[]::new);
  }

  @PostConstruct
  void validate() {
    if (!StringUtils.hasText(allowedOrigins)) {
      throw new IllegalStateException("必须配置 IM_ACCESS_WS_ALLOWED_ORIGINS");
    }
    if (allowedOriginsArray().length == 0
        || Arrays.stream(allowedOriginsArray()).anyMatch(origin -> !isAllowedOrigin(origin))) {
      throw new IllegalStateException("IM_ACCESS_WS_ALLOWED_ORIGINS 必须为明确的 http 或 https Origin 列表");
    }
    if (maxTextMessageBytes < 1) {
      throw new IllegalStateException("IM_ACCESS_WS_MAX_TEXT_MESSAGE_BYTES 必须大于 0");
    }
  }

  private boolean isAllowedOrigin(String origin) {
    try {
      URI uri = URI.create(origin);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && StringUtils.hasText(uri.getHost())
          && uri.getQuery() == null
          && uri.getFragment() == null
          && uri.getUserInfo() == null
          && uri.getPath().isEmpty();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
