package com.gvchat.im.conversation.controller;

import com.gvchat.im.conversation.api.admin.AdminConversationStatsResponse;
import com.gvchat.im.conversation.application.group.GroupApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/conversations")
@RequiredArgsConstructor
public class InternalAdminConversationQueryController {
  private final GroupApplicationService groupApplicationService;

  @GetMapping("/stats")
  public AdminConversationStatsResponse stats(@RequestParam(defaultValue = "0") int days) {
    GroupApplicationService.GroupStats stats = groupApplicationService.stats(days);
    return new AdminConversationStatsResponse(stats.totalGroups(), stats.recentGroups(), stats.dailySeries());
  }
}
