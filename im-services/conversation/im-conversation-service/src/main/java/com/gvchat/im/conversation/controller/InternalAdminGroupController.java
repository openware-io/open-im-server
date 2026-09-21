package com.gvchat.im.conversation.controller;

import com.gvchat.im.conversation.application.group.GroupApplicationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/admin/groups")
@RequiredArgsConstructor
public class InternalAdminGroupController {
  private final GroupApplicationService groupService;

  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String keyword) {
    return groupService.listAdminGroups(keyword, page, pageSize);
  }

  @GetMapping("/{id}/members")
  public List<Map<String, Object>> members(@PathVariable Long id) {
    return groupService.listGroupMembers(id);
  }

  @DeleteMapping("/{id}")
  public void dissolve(@PathVariable Long id) {
    groupService.adminDissolveGroup(id);
  }
}
