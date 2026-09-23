package io.openware.im.user.application.cancellation.result;

import java.time.LocalDateTime;
import java.util.List;

public record AccountCancellationStatusResult(
    Long applicationId,
    String status,
    List<AccountCancellationStepResult> steps,
    LocalDateTime requestedAt,
    LocalDateTime completedAt,
    String failureReason) {
}
