package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchAddUserStickerRequest {
  @NotNull
  private List<String> urls;
}
