package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** IDaaS SSO 免二次登录请求：携带一次性 SSO 票据。 */
public record SsoLoginRequest(@NotBlank(message = "ticket is required") String ticket) {
}
