package com.gvchat.common.audit.api.dto;

import com.gvchat.common.audit.application.AuditScopeResolver;
import com.gvchat.common.audit.domain.model.AuditLog;
import com.gvchat.common.audit.domain.repository.AuditLogRepository;
import com.gvchat.common.exception.ApiException;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 审计查询请求（{@code GET /admin/audits} 的筛选参数，前端按此对接）。
 *
 * <table>
 *   <caption>参数口径</caption>
 *   <tr><th>参数</th><th>含义</th><th>说明</th></tr>
 *   <tr><td>page / pageSize</td><td>分页</td><td>page 从 1 开始；pageSize 1..200，默认 20</td></tr>
 *   <tr><td>tenantId</td><td>租户过滤</td><td>平台视角可用；租户视角传其它租户 403</td></tr>
 *   <tr><td>organizationId / storeId</td><td>组织/门店过滤</td><td>可选</td></tr>
 *   <tr><td>operatorId</td><td>操作人 ID</td><td>精确匹配</td></tr>
 *   <tr><td>operatorKeyword</td><td>操作人关键字</td><td>匹配 operator_name 模糊或 operator_id 精确</td></tr>
 *   <tr><td>action</td><td>动作码</td><td>精确匹配，如 order.settle</td></tr>
 *   <tr><td>actionPrefix</td><td>动作码前缀</td><td>如 order. 查订单域全部动作</td></tr>
 *   <tr><td>resourceType / resourceId</td><td>资源对象</td><td>精确匹配</td></tr>
 *   <tr><td>result</td><td>结果</td><td>SUCCESS / FAILURE</td></tr>
 *   <tr><td>operatorType</td><td>操作人类型</td><td>PLATFORM / TENANT</td></tr>
 *   <tr><td>requestId / traceId</td><td>链路定位</td><td>精确匹配</td></tr>
 *   <tr><td>fromAt / toAt</td><td>时间区间（左闭右开）</td><td>支持 yyyy-MM-dd HH:mm:ss、ISO-8601、epoch 毫秒</td></tr>
 *   <tr><td>occurredFrom / occurredTo</td><td>fromAt / toAt 的别名</td><td>兼容不同前端命名</td></tr>
 *   <tr><td>order</td><td>排序</td><td>desc（默认，新→旧）/ asc</td></tr>
 *   <tr><td>skipCount</td><td>跳过总数统计</td><td>第 2 页起由前端回传；命中时 {@code total=-1}，前端沿用上一页总数</td></tr>
 * </table>
 */
public record AuditQueryRequest(
        int page,
        int pageSize,
        Long tenantId,
        Long organizationId,
        Long storeId,
        Long operatorId,
        String operatorKeyword,
        String action,
        String actionPrefix,
        String resourceType,
        String resourceId,
        String result,
        String operatorType,
        String requestId,
        String traceId,
        LocalDateTime from,
        LocalDateTime to,
        boolean ascending,
        boolean skipCount) {

    /** 单页最大条数：受限上限避免一次拉全表把审计库打满。 */
    public static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> RESULTS = Set.of(AuditLog.RESULT_SUCCESS, AuditLog.RESULT_FAILURE);
    private static final Set<String> OPERATOR_TYPES =
            Set.of(AuditLog.OPERATOR_TYPE_PLATFORM, AuditLog.OPERATOR_TYPE_TENANT);

    /**
     * 结合调用方视角生成仓储查询条件：租户视角**强制**收敛到上下文租户。
     *
     * @param countCap   总数统计上界（服务端配置，不接受客户端指定，避免被用来放大查询成本）
     * @param floor      可查下限（保留策略）；早于它的月份已被归档并移出可查范围。
     *                   请求的 {@code from} 早于下限时**收敛到下限**，不让查询去扫已归档的月份。
     */
    public AuditLogRepository.Query toQuery(AuditScopeResolver.Resolution resolution, long countCap,
                                            LocalDateTime floor) {
        Long effectiveTenantId = resolution.isPlatform() ? tenantId : resolution.tenantId();
        long offset = (long) (page - 1) * pageSize;
        LocalDateTime effectiveFrom = floor != null && (from == null || from.isBefore(floor)) ? floor : from;
        return new AuditLogRepository.Query(effectiveTenantId, organizationId, storeId, operatorId,
                operatorKeyword, action, actionPrefix, resourceType, resourceId, result, operatorType, requestId,
                traceId, effectiveFrom, to, ascending, offset, pageSize, countCap);
    }

    /** 结果/操作人类型等枚举参数的大小写归一与校验（非法 400）。 */
    public AuditQueryRequest normalized() {
        return new AuditQueryRequest(page, pageSize, tenantId, organizationId, storeId, operatorId,
                trim(operatorKeyword), trim(action), trim(actionPrefix), trim(resourceType), trim(resourceId),
                enumValue(result, RESULTS, "result"), enumValue(operatorType, OPERATOR_TYPES, "operatorType"),
                trim(requestId), trim(traceId), from, to, ascending, skipCount);
    }

    private static String enumValue(String raw, Set<String> allowed, String field) {
        String value = trim(raw);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new ApiException(400, "INVALID_ARGUMENT", field + " 只允许 " + allowed + "，实际: " + raw);
        }
        return upper;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
