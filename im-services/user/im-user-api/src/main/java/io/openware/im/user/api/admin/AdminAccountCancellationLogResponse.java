package io.openware.im.user.api.admin;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAccountCancellationLogResponse {
  private final Long id;
  private final Long cancellationId;
  private final Long userId;
  private final String action;
  private final String detail;
  private final String operatorType;
  private final Long operatorId;
  private final String ip;
  private final LocalDateTime occurredAt;
}
