package com.gvchat.im.admin.controller;

import com.gvchat.im.admin.application.query.AdminMonitorApplicationService;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@RequestMapping("/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {
  private final AdminMonitorApplicationService adminMonitorService;

/**
 */
  @GetMapping("/online")
  public Map<String, Object> onlineStats() {
    return adminMonitorService.getOnlineStats();
  }

  @GetMapping("/online/users")
  public List<Map<String, Object>> onlineUsers() {
    return adminMonitorService.getOnlineUsers();
  }

  @GetMapping("/calls")
  public List<Map<String, Object>> callRooms() {
    return adminMonitorService.getCallRooms();
  }

  @GetMapping("/server")
  public Map<String, Object> serverStats() {
    return adminMonitorService.getServerStats();
  }
}

