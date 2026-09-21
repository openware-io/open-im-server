package com.gvchat.im.admin.application.query;

import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.constant.RedisKeys;
import com.gvchat.im.user.api.admin.AdminUserResponse;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminMonitorApplicationService {
  private final StringRedisTemplate redisTemplate;
  private final AdminReadClient adminReadClient;

  public Map<String, Object> getOnlineStats() {
    Set<String> members = redisTemplate.opsForSet().members(RedisKeys.ONLINE_SET);
    int onlineCount = members == null ? 0 : members.size();
    return Map.of("onlineCount", onlineCount, "updatedAt", Instant.now().toString());
  }

  public List<Map<String, Object>> getOnlineUsers() {
    Set<String> members = redisTemplate.opsForSet().members(RedisKeys.ONLINE_SET);
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    return members.stream().map(this::toOnlineUser).toList();
  }

  public List<Map<String, Object>> getCallRooms() {
    return List.of();
  }

  public Map<String, Object> getServerStats() {
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    long usedMemory = totalMemory - freeMemory;
    long totalMemoryMb = totalMemory / (1024 * 1024);
    long freeMemoryMb = freeMemory / (1024 * 1024);
    long memoryPercent = maxMemory == 0 ? 0 : Math.round(usedMemory * 100.0 / maxMemory);
    double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    long cpuPercent = load < 0 ? 0 : Math.min(100, Math.round(load * 100 / runtime.availableProcessors()));
    return Map.of("cpu", cpuPercent, "memory", memoryPercent, "freeMemory", freeMemoryMb,
        "totalMemory", totalMemoryMb, "wsConnections", getOnlineStats().get("onlineCount"),
        "uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
        "hostname", hostname(), "platform", System.getProperty("os.name"),
        "arch", System.getProperty("os.arch"), "nodeVersion", System.getProperty("java.version"));
  }

  private Map<String, Object> toOnlineUser(String member) {
    try {
      Long userId = Long.valueOf(member);
      AdminUserResponse user = adminReadClient.getUser(userId);
      Long sockets = redisTemplate.opsForSet().size(RedisKeys.USER_SOCKETS + userId);
      return Map.of("userId", userId, "username", user.getUsername(), "nickname", user.getNickname(),
          "avatar", user.getAvatar() == null ? "" : user.getAvatar(), "socketCount", sockets == null ? 0 : sockets);
    } catch (RuntimeException ex) {
      return Map.of("userId", member, "username", member, "nickname", member, "avatar", "", "socketCount", 0);
    }
  }

  private String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception ex) {
      return "unknown";
    }
  }
}
