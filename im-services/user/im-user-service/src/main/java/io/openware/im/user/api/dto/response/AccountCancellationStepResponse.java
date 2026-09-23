package io.openware.im.user.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountCancellationStepResponse {
  private final String code;
  private final boolean completed;
}
