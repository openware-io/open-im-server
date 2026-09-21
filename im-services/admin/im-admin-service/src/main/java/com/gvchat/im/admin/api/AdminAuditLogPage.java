package com.gvchat.im.admin.api;

import java.util.List;
import java.util.Map;

/**
 * IM 后台「审计日志」页返回体：与 common-audit-service 的分页结构对齐（items/total/page/pageSize）。
 *
 * @param items      审计记录（字段由审计服务定义：occurredAt/action/actionLabel/resourceType/resourceId/
 *                   resourceName/operatorName/operatorAccount/result/errorCode 等；{@code detailJson} 是
 *                   **JSON 文本**（String），不是节点对象 —— 审计服务侧的投影口径见
 *                   {@code common-audit-service} 的 {@code AuditLogView}：Spring Boot 4 的 Jackson 3
 *                   消息转换器会把 Jackson 2 的 {@code JsonNode} 当普通 POJO 序列化成节点元数据，
 *                   业务内容整段丢失，所以这条链路上一律用 String 承载 JSON 文本）
 * @param total      总数；{@code -1} 表示本次请求跳过了统计（翻页场景沿用了上一页总数）
 * @param page       当前页
 * @param pageSize   每页条数
 * @param totalPages 总页数
 * @param totalCapped 总数是否已被统计上界封顶（前端显示 N+）
 * @param retentionFloor 可查下限；早于该时间的月份已归档、不在可查范围
 */
public record AdminAuditLogPage(
    List<Map<String, Object>> items,
    long total,
    int page,
    int pageSize,
    int totalPages,
    boolean totalCapped,
    String retentionFloor) {

  /** 从审计服务返回的原始 Map 组装（缺字段时给安全缺省值，不让前端拿到 null 总数）。 */
  @SuppressWarnings("unchecked")
  public static AdminAuditLogPage from(Map<String, Object> response) {
    if (response == null || response.isEmpty()) {
      return new AdminAuditLogPage(List.of(), 0L, 1, 20, 0, false, null);
    }
    Object rawItems = response.get("items");
    List<Map<String, Object>> items = rawItems instanceof List<?> list
        ? (List<Map<String, Object>>) list
        : List.of();
    return new AdminAuditLogPage(items, longValue(response.get("total")), intValue(response.get("page"), 1),
        intValue(response.get("pageSize"), 20), intValue(response.get("totalPages"), 0),
        Boolean.TRUE.equals(response.get("totalCapped")),
        response.get("retentionFloor") == null ? null : String.valueOf(response.get("retentionFloor")));
  }

  private static long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static int intValue(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }
}
