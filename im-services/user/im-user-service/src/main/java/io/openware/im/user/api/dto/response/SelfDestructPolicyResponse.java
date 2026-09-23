package io.openware.im.user.api.dto.response;

import java.time.LocalDateTime;

/** 账号自毁策略响应：当前策略值、到期硬删时间、最近活跃时间。 */
public record SelfDestructPolicyResponse(String policy, LocalDateTime selfDestructAt, LocalDateTime lastLoginAt) {
}
