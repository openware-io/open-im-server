package com.gvchat.common.audit.api.dto;

/**
 * 内部服务审计查询入参（{@code POST /internal/audit/records/search}）。
 *
 * <p>为什么单独一个「字符串时间」DTO：内部通道调用方（如 IM 后台）传的是界面筛选值
 * （{@code 2026-09-20} 这类日期），必须与公开端点 {@code GET /admin/audits} 用同一套时间口径
 * （起始端取当天 {@code 00:00:00.000}、结束端取**次日** {@code 00:00:00} 的排他上界），
 * 解析放在服务端做，避免各调用方各写一套。
 *
 * @param page            页码，从 1 开始，缺省 1
 * @param pageSize        每页条数，缺省 20，上限 {@link AuditQueryRequest#MAX_PAGE_SIZE}
 * @param action          动作码精确匹配，如 {@code im-user.delete}
 * @param actionPrefix    动作码前缀匹配，如 {@code im-user.}
 * @param resourceType    资源类型精确匹配，如 {@code user_account}
 * @param resourceId      资源 ID 精确匹配
 * @param operatorKeyword 操作人关键字（姓名模糊 / ID 精确）
 * @param result          结果 SUCCESS / FAILURE
 * @param from            起始时间（含），支持 {@code yyyy-MM-dd}、ISO-8601、epoch 毫秒
 * @param to              结束时间（排他），日期形态按次日零点计算
 * @param ascending       是否升序（缺省按时间倒序）
 * @param skipCount       是否跳过总数统计（第 2 页起由调用方回传）
 */
public record AuditInternalSearchRequest(
        Integer page,
        Integer pageSize,
        String action,
        String actionPrefix,
        String resourceType,
        String resourceId,
        String operatorKeyword,
        String result,
        String from,
        String to,
        Boolean ascending,
        Boolean skipCount) {
}
