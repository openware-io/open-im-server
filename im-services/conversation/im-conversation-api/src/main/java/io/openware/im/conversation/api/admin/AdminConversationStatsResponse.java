package io.openware.im.conversation.api.admin;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminConversationStatsResponse {
  private final long groupCount;
  private final long newGroups;
  private final List<Map<String, Object>> dailySeries;
}
