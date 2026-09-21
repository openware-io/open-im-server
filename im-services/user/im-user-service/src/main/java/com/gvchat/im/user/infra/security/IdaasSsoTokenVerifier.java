package com.gvchat.im.user.infra.security;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;

/**
 * 校验集团 IDaaS 签发的 SSO Token（与 /idaas/auth/sso/verify 使用同一共享 jwt.secret），
 * 提取其中的 username，供后台免二次登录映射本地管理员账号。
 */
@Component
public class IdaasSsoTokenVerifier {

  private final JwtTokenProvider jwtTokenProvider;

  public IdaasSsoTokenVerifier(JwtTokenProvider jwtTokenProvider) {
    this.jwtTokenProvider = jwtTokenProvider;
  }

  /** 校验 IDaaS Token 并返回 username。无效或缺失 username 时抛出 401 ApiException。 */
  public String verifyUsername(String idaasToken) {
    try {
      Claims claims = jwtTokenProvider.parseClaims(idaasToken);
      String username = claims.get("username", String.class);
      if (username == null || username.isBlank()) {
        throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SSO_IDAAS_TOKEN_INVALID",
            "IDaaS token missing username claim");
      }
      return username;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SSO_IDAAS_TOKEN_INVALID",
          "invalid or expired IDaaS SSO token");
    }
  }
}
