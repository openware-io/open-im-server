package io.openware.im.admin.controller;

import io.openware.im.admin.api.AdminAuditLogPage;
import io.openware.im.admin.application.query.AdminAuditLogApplicationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 后台「审计日志」：读 common-audit-service 的同一套审计（与 SaaS 后台同源）。
 *
 * <p>路由 {@code /admin/audit-logs} 刻意区别于 SaaS 审计查询的 {@code /admin/audits}：
 * 后者由网关的 SaaS 会话过滤器保护、需要 {@code X-Tenant-Context} 运营上下文，
 * IM 后台用的是自己的管理员 JWT，走本端点（{@code /api/v1/admin/**} → im-admin-service）。
 */
@RestController
@Tag(name = "审计日志")
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

  private final AdminAuditLogApplicationService adminAuditLogApplicationService;

  @GetMapping
  public AdminAuditLogPage list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String operator,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return adminAuditLogApplicationService.list(page, pageSize, action, resourceType, operator, result, from, to);
  }
}
