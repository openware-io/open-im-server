package com.gvchat.im.admin.application.query;

import com.gvchat.im.admin.application.AdminManagementApplicationService;
import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.dto.StatsQueryDto;
import com.gvchat.im.conversation.api.admin.AdminConversationStatsResponse;
import com.gvchat.im.message.api.admin.AdminMessageStatsResponse;
import com.gvchat.im.user.api.admin.AdminUserStatsResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminStatsApplicationService {
  private static final long CACHE_TTL_MS = 60_000L;

  private final AdminManagementApplicationService managementApplicationService;
  private final AdminReadClient adminReadClient;
  private final AdminMonitorApplicationService adminMonitorApplicationService;

  private volatile Map<String, Object> overviewCache;
  private volatile long overviewCachedAt;
  private volatile Map<String, Object> trendCache;
  private volatile long trendCachedAt;

  /** 数据看板概览：用户/好友/群/消息总数 + 今日新增 + 真实在线数 + 待处理举报；60s 进程内缓存。 */
  public Map<String, Object> getOverview() {
    long now = System.currentTimeMillis();
    if (overviewCache != null && now - overviewCachedAt < CACHE_TTL_MS) {
      return overviewCache;
    }
    AdminUserStatsResponse userStats = adminReadClient.userStats(0);
    AdminMessageStatsResponse messageStats = adminReadClient.messageStats(0);
    AdminConversationStatsResponse conversationStats = adminReadClient.conversationStats(0);
    int onlineCount = ((Number) adminMonitorApplicationService.getOnlineStats().get("onlineCount")).intValue();

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("totalUsers", userStats.getUserCount());
    stats.put("todayNewUsers", userStats.getNewUsers());
    stats.put("totalFriends", userStats.getFriendCount());
    stats.put("todayNewFriends", userStats.getNewFriends());
    stats.put("totalGroups", conversationStats.getGroupCount());
    stats.put("todayNewGroups", conversationStats.getNewGroups());
    stats.put("totalMessages", messageStats.getMessageCount());
    stats.put("todayNewMessages", messageStats.getNewMessages());
    stats.put("dau", messageStats.getActiveUsers());
    stats.put("onlineCount", onlineCount);
    stats.put("pendingReports", managementApplicationService.pendingReportCount());
    stats.put("updatedAt", Instant.now().toString());

    // 兼容旧字段名（前端旧版使用）
    stats.put("userCount", userStats.getUserCount());
    stats.put("usersTotal", userStats.getUserCount());
    stats.put("friendCount", userStats.getFriendCount());
    stats.put("groupCount", conversationStats.getGroupCount());
    stats.put("groupsTotal", conversationStats.getGroupCount());
    stats.put("messageCount", messageStats.getMessageCount());
    stats.put("messagesTotal", messageStats.getMessageCount());

    overviewCache = stats;
    overviewCachedAt = now;
    return stats;
  }

  /** 趋势：返回按天时间序列 points（近 [days] 天），60s 进程内缓存。 */
  public Map<String, Object> getTrend(StatsQueryDto query) {
    int days = query.getDays() == null ? 7 : Math.max(1, Math.min(query.getDays(), 90));
    long now = System.currentTimeMillis();
    if (trendCache != null && trendCache.containsKey("days")
        && Integer.valueOf((Integer) trendCache.get("days")) == days && now - trendCachedAt < CACHE_TTL_MS) {
      return trendCache;
    }
    AdminUserStatsResponse userStats = adminReadClient.userStats(days);
    AdminMessageStatsResponse messageStats = adminReadClient.messageStats(days);
    AdminConversationStatsResponse conversationStats = adminReadClient.conversationStats(days);

    List<Map<String, Object>> points = mergeDailySeries(days,
        userStats.getDailyNewUsers(), userStats.getDailyNewFriends(),
        conversationStats.getDailySeries(), messageStats.getDailySeries());

    Map<String, Object> trend = new LinkedHashMap<>();
    trend.put("days", days);
    trend.put("points", points);
    trend.put("newUsers", userStats.getNewUsers());
    trend.put("newFriends", userStats.getNewFriends());
    trend.put("newGroups", conversationStats.getNewGroups());
    trend.put("newMessages", messageStats.getNewMessages());
    trend.put("updatedAt", Instant.now().toString());

    trendCache = trend;
    trendCachedAt = now;
    return trend;
  }

  private List<Map<String, Object>> mergeDailySeries(int days,
      List<Map<String, Object>> newUsers, List<Map<String, Object>> newFriends,
      List<Map<String, Object>> newGroups, List<Map<String, Object>> newMessages) {
    LocalDate today = LocalDate.now(java.time.Clock.systemUTC());
    TreeMap<String, Map<String, Object>> byDate = new TreeMap<>();
    for (int i = days - 1; i >= 0; i--) {
      String date = today.minusDays(i).toString();
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("date", date);
      point.put("newUsers", 0L);
      point.put("newFriends", 0L);
      point.put("newGroups", 0L);
      point.put("newMessages", 0L);
      byDate.put(date, point);
    }
    fill(byDate, newUsers, "newUsers");
    fill(byDate, newFriends, "newFriends");
    fill(byDate, newGroups, "newGroups");
    fill(byDate, newMessages, "newMessages");
    return new ArrayList<>(byDate.values());
  }

  private void fill(Map<String, Map<String, Object>> byDate, List<Map<String, Object>> series, String key) {
    if (series == null) return;
    for (Map<String, Object> row : series) {
      String date = row.get("date") == null ? null : row.get("date").toString();
      if (date == null) continue;
      Map<String, Object> point = byDate.get(date);
      if (point == null) continue;
      Object count = row.get("count");
      point.put(key, count == null ? 0L : Long.parseLong(count.toString()));
    }
  }
}
