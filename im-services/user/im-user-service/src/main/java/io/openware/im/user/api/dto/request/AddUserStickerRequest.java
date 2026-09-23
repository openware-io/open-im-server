package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddUserStickerRequest {
  @NotBlank
  private String url;
  private String thumbnail;
}
