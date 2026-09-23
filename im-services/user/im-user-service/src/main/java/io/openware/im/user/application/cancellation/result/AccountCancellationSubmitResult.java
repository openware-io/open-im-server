package io.openware.im.user.application.cancellation.result;

public record AccountCancellationSubmitResult(Long applicationId, String status, String statusToken) {
}
