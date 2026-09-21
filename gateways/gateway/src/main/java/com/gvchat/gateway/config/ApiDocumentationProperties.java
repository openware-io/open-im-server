package com.gvchat.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials that protect the externally reachable API documentation surface. */
@ConfigurationProperties("im.documentation")
public class ApiDocumentationProperties {
  private String username;
  private String password;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public void validate() {
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "IM_DOCUMENTATION_USERNAME and IM_DOCUMENTATION_PASSWORD must be configured");
    }
  }
}
