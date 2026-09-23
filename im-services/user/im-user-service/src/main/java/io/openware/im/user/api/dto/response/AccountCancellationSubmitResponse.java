package io.openware.im.user.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountCancellationSubmitResponse {
  private final Long applicationId;
  private final String status;
  private final String statusToken;
}
