package com.gvchat.im.user.application.account.result;

import java.time.LocalDateTime;

/** 账号自毁策略视图：当前策略值、到期硬删时间、最近活跃时间。 */
public record SelfDestructPolicyResult(String policy, LocalDateTime selfDestructAt, LocalDateTime lastLoginAt) {
}
