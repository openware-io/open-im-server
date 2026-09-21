package com.gvchat.platform.identity.infra.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * IM 开放平台（OAuth Provider）接入配置。
 * base-url/appId/appSecret 通过环境变量 IM_SERVICE_BASE_URL / IM_OAUTH_APP_ID / IM_OAUTH_APP_SECRET 覆盖。
 */
@Component
@ConfigurationProperties(prefix = "im.oauth")
public class ImOAuthProperties {
    private String baseUrl = "http://localhost:3100";
    private String appId = "";
    private String appSecret = "";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
}
