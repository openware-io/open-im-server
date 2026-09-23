package io.openware.im.user.api.admin;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminUserStatsResponse {
  private final long userCount;
  private final long newUsers;
  private final long friendCount;
  private final long newFriends;
  private final List<Map<String, Object>> dailyNewUsers;
  private final List<Map<String, Object>> dailyNewFriends;
}
