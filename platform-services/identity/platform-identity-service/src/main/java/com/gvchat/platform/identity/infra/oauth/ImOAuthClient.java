package com.gvchat.platform.identity.infra.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import org.springframework.util.LinkedMultiValueMap;

/**
 * SaaS 侧 OAuth Client：调用 IM 开放平台。授权码换 token（用 appId+appSecret）与用户级授权身份（open_id + 资料）。
 */
@Component
public class ImOAuthClient {
    private final RestClient restClient;
    private final String appId;
    private final String appSecret;

    public ImOAuthClient(ImOAuthProperties properties) {
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
        this.appId = properties.getAppId();
        this.appSecret = properties.getAppSecret();
    }

    /** 授权码换 im_access_token（appId 由调用方传入，C/B 共用 appSecret + PKCE verifier）。 */
    public String exchangeCode(String code, String codeVerifier, String redirectUri, String appId) {
        String resolvedAppId = appId == null || appId.isBlank() ? this.appId : appId;
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("code", code == null ? "" : code);
            form.add("code_verifier", codeVerifier == null ? "" : codeVerifier);
            form.add("redirect_uri", redirectUri == null ? "" : redirectUri);
            TokenResponse token = restClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> headers.setBasicAuth(resolvedAppId, appSecret))
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            return token == null || token.accessToken() == null ? "" : token.accessToken();
        } catch (RestClientResponseException ex) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "IM_TOKEN_EXCHANGE_FAILED",
                    "IM 令牌换取失败: " + ex.getStatusCode().value());
        }
    }

    public record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    public ImOAuthUserInfo fetchUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri("/oauth/userinfo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(ImOAuthUserInfo.class);
        } catch (RestClientResponseException ex) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "IM_USERINFO_FAILED",
                    "IM 用户信息获取失败: " + ex.getStatusCode().value());
        }
    }
}
