package io.openware.im.admin.application.query;

import io.openware.im.admin.api.AdminAuditLogPage;
import io.openware.im.admin.integration.AuditQueryClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * IM 后台「审计日志」查询服务：透传到 common-audit-service，不新造存储。
 *
 * <p>过滤项与 SaaS 后台审计页同一套语义：动作码、资源类型精确匹配，操作人关键字按姓名模糊/ID 精确，
 * 时间区间走统一的 {@code from}/{@code to} 口径（日期形态自动收口到整天），排序默认最新在前。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditLogApplicationService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;

  private final AuditQueryClient auditQueryClient;

  public AdminAuditLogPage list(Integer page, Integer pageSize, String action, String resourceType,
      String operator, String result, String from, String to) {
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("page", page == null || page < 1 ? DEFAULT_PAGE : page);
    int effectivePageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
    filters.put("pageSize", Math.min(effectivePageSize, MAX_PAGE_SIZE));
    putIfPresent(filters, "action", action);
    putIfPresent(filters, "resourceType", resourceType);
    putIfPresent(filters, "operatorKeyword", operator);
    putIfPresent(filters, "result", result);
    putIfPresent(filters, "from", from);
    putIfPresent(filters, "to", to);
    return AdminAuditLogPage.from(auditQueryClient.search(filters));
  }

  private static void putIfPresent(Map<String, Object> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value.trim());
    }
  }
}
