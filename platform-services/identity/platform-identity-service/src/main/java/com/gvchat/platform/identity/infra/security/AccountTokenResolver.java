package com.gvchat.platform.identity.infra.security;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Component;

/**
 * 解析登录签发的 Access Token（subject = accountId），替代明文 X-Account-Id 透传。
 * 第三方授权 / 经营上下文等需要身份识别的场景统一经此校验签名。
 */
@Component
public class AccountTokenResolver {
    private final JwtTokenProvider jwtTokenProvider;

    public AccountTokenResolver(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 从 Authorization: Bearer 头解析 accountId；缺失/格式错误/签名非法/过期统一抛 401。 */
    public Long resolveAccountId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "AUTH_TOKEN_REQUIRED", "缺少访问令牌");
        }
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "AUTH_TOKEN_REQUIRED", "缺少访问令牌");
        }
        String token = trimmed.substring(7).trim();
        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "访问令牌无效或已过期");
        }
    }
}
