package io.openware.im.conversation.controller;

import io.openware.im.conversation.api.admin.AdminConversationStatsResponse;
import io.openware.im.conversation.application.group.GroupApplicationService;
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
