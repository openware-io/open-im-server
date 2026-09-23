package io.openware.im.admin.domain.moderation;

import io.openware.common.enums.ReportStatus;
import java.time.LocalDateTime;

public record Report(Long id, Long reporterId, Long targetId, String reason, String description, String evidence,
                     ReportStatus status, Long handledBy, String handleRemark, LocalDateTime handledAt,
                     LocalDateTime createdAt, LocalDateTime updatedAt) {
}
