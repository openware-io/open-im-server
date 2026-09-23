package io.openware.im.message.infra.integration.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 管理服务内部地址配置。 */
@Component
@ConfigurationProperties(prefix = "im.admin")
public class AdminServiceProperties {
  private String baseUrl;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }
}
