package com.gvchat.im.admin.controller;

import com.gvchat.common.dto.StatsQueryDto;
import com.gvchat.im.admin.application.query.AdminStatsApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {
  private final AdminStatsApplicationService adminStatsService;

/**
 */
  @GetMapping("/overview")
  public Map<String, Object> overview() {
    return adminStatsService.getOverview();
  }

/**
 */
  @GetMapping("/trend")
  public Map<String, Object> trend(@ModelAttribute StatsQueryDto dto) {
    return adminStatsService.getTrend(dto);
  }
}

