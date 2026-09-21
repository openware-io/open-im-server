package com.gvchat.platform.identity.infra.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * IM 开放平台 GET /oauth/userinfo 的响应契约。
 */
public record ImOAuthUserInfo(
        @JsonProperty("open_id") String openId,
        String nickname,
        String avatar,
        String phone,
        String scope) {
}
