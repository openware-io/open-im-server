package com.gvchat.im.admin.controller;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.admin.application.query.AdminAccountCancellationApplicationService;
import com.gvchat.im.user.api.admin.AdminAccountCancellationLogResponse;
import com.gvchat.im.user.api.admin.AdminAccountCancellationResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号注销申请管理：列表 + 审计日志。 */
@RestController
@Tag(name = "账号注销申请管理")
@RequestMapping("/admin/account-cancellations")
@RequiredArgsConstructor
public class AdminAccountCancellationController {
  private final AdminAccountCancellationApplicationService service;

  @GetMapping
  public PageResult<AdminAccountCancellationResponse> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return service.list(page, pageSize, userId, status, keyword);
  }

  @GetMapping("/{id}/logs")
  public List<AdminAccountCancellationLogResponse> logs(@PathVariable Long id) {
    return service.listLogs(id);
  }

  @GetMapping("/logs")
  public PageResult<AdminAccountCancellationLogResponse> searchLogs(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long cancellationId,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String action) {
    return service.searchLogs(page, pageSize, cancellationId, userId, action);
  }
}
