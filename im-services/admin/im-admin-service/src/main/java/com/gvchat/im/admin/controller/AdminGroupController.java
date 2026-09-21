package com.gvchat.im.admin.controller;

import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.dto.PageResult;
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
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class AdminGroupController {
  private final AdminReadClient client;

  @GetMapping
  public PageResult<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String keyword) {
    return client.listGroups(page, pageSize, keyword);
  }

  @GetMapping("/{id}/members")
  public List<Map<String, Object>> members(@PathVariable Long id) {
    return client.listGroupMembers(id);
  }

  @DeleteMapping("/{id}")
  public void dissolve(@PathVariable Long id) {
    client.dissolveGroup(id);
  }
}
