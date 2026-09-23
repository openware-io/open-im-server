package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearchUserRequest {
  @NotBlank
  private String keyword;
}
