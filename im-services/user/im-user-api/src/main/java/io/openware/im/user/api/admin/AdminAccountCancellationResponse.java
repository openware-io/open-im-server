package io.openware.im.user.api.admin;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAccountCancellationResponse {
  private final Long id;
  private final Long userId;
  private final String username;
  private final String nickname;
  private final String phone;
  private final String email;
  private final String status;
  private final String source;
  private final List<String> completedSteps;
  private final String requestedIp;
  private final LocalDateTime requestedAt;
  private final LocalDateTime processingAt;
  private final LocalDateTime completedAt;
  private final String failureReason;
}
