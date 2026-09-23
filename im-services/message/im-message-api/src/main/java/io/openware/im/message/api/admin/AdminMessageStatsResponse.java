package io.openware.im.message.api.admin;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMessageStatsResponse {
  private final long messageCount;
  private final long newMessages;
  private final List<Map<String, Object>> dailySeries;
  private final long activeUsers;
}
