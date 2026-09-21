package com.gvchat.common.audit.api.dto;

import java.util.List;

/**
 * 审计查询分页返回体。
 *
 * <p>{@code scope} 与 {@code effectiveTenantId} 是服务端视角的显式回执：前端能直接看到本次查询
 * 是「平台全量」还是「被收敛到某个租户」，无需猜测权限分层是否生效。
 *
 * <p>总数语义（配合 {@code skipCount} 与 countCap）：
 * <ul>
 *   <li>{@code total >= 0}：精确总数，或已到达统计上界（此时 {@code totalCapped=true}，
 *       真实总数可能更大，前端应显示「N+」）；</li>
 *   <li>{@code total = -1}：本次请求带了 {@code skipCount}，服务端按约定**跳过了总数统计**，
 *       前端必须沿用上一次同筛选条件的 total（翻页场景），不得显示为 0。</li>
 * </ul>
 *
 * <p>{@code retentionFloor} 是保留策略的显式回执：早于该时间的月份已登记归档、移出可查范围
 * （本阶段数据仍在库里、不删分区）。为 {@code null} 表示当前没有任何月份被归档。
 * 前端应据此提示「更早记录已归档」，而不是让运营以为「查不到就是没有」。
 */
public record AuditPageView(
        String scope,
        Long effectiveTenantId,
        int page,
        int pageSize,
        long total,
        int totalPages,
        boolean totalCapped,
        String retentionFloor,
        List<AuditLogView> items) {

    /** {@code total} 的哨兵值：本次查询跳过了总数统计（详见类注释）。 */
    public static final long TOTAL_SKIPPED = -1L;
}
