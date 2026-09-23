package io.openware.im.admin.domain.moderation;

import io.openware.common.enums.ViolationAction;
import java.time.LocalDateTime;

public record Violation(Long id, Long userId, String reason, String content, String msgId, ViolationAction action,
                        String remark, LocalDateTime createdAt) {
}
