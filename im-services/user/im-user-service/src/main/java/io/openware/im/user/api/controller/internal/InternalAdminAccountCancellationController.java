package io.openware.im.user.api.controller.internal;

import io.openware.common.dto.PageResult;
import io.openware.im.user.api.admin.AdminAccountCancellationLogResponse;
import io.openware.im.user.api.admin.AdminAccountCancellationResponse;
import io.openware.im.user.application.cancellation.AccountCancellationAdminQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号注销申请内部管理接口：供 im-admin-service 代理查询列表与审计日志。 */
@RestController
@RequestMapping("/internal/admin/account-cancellations")
@RequiredArgsConstructor
public class InternalAdminAccountCancellationController {
  private final AccountCancellationAdminQueryService queryService;

  @GetMapping
  public PageResult<AdminAccountCancellationResponse> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return queryService.list(userId, status, keyword, page, pageSize);
  }

  @GetMapping("/{id}/logs")
  public List<AdminAccountCancellationLogResponse> logs(@PathVariable Long id) {
    return queryService.listLogs(id);
  }

  @GetMapping("/logs")
  public PageResult<AdminAccountCancellationLogResponse> searchLogs(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long cancellationId,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String action) {
    return queryService.searchLogs(cancellationId, userId, action, page, pageSize);
  }
}
