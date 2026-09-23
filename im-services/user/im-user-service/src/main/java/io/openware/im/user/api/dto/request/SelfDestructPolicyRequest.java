package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 设置账号自毁策略请求（off/1mo/3mo/6mo/1yr）。 */
public record SelfDestructPolicyRequest(@NotBlank String policy) {
}
