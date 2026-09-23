package io.openware.im.message.api.admin;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMessagePageResponse {
  private final List<AdminMessageResponse> items;
  private final long total;
  private final int page;
  private final int pageSize;
}
