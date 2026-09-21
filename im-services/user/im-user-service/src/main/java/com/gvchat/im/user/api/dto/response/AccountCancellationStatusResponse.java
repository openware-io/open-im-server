package com.gvchat.im.user.api.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountCancellationStatusResponse {
  private final Long applicationId;
  private final String status;
  private final List<AccountCancellationStepResponse> steps;
  private final LocalDateTime requestedAt;
  private final LocalDateTime completedAt;
  private final String failureReason;
}
