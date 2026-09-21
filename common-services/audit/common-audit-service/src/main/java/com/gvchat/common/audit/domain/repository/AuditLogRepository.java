package com.gvchat.common.audit.domain.repository;

import com.gvchat.common.audit.domain.model.AuditLog;
import com.gvchat.common.audit.domain.model.OperatorDisplay;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审计日志仓储：写入按 {@code (tenant_id, idempotency_key)} 幂等，查询只提供只读能力
 * （审计记录不可修改、不可物理删除）。
 */
public interface AuditLogRepository {

    /** 幂等写入结果。 */
    record SaveResult(long id, boolean duplicated) { }

    /**
     * 分页查询条件（租户收敛由应用层完成，仓储层只做过滤）。
     *
     * @param countCap 总数统计的上界：{@link #count} 最多证明「不少于这么多」，
     *                 由应用层据此判定是否已封顶；避免在超大结果集上做无上界全量聚合。
     */
    record Query(
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
            long offset,
            int size,
            long countCap) { }

    /** 幂等写入：命中唯一键时不再插入，返回既有记录 ID 与 duplicated=true。 */
    SaveResult saveIfAbsent(AuditLog log);

    /**
     * 批量幂等写入：返回顺序与入参一一对应（调用方按位置回执逐条 ID）。
     *
     * <p>默认实现退化为逐条 {@link #saveIfAbsent}（语义等价、便于替身实现）；
     * 生产实现覆写为「台账逐条抢占 + 主表一次多行插入」，把批量上报的写入成本压下来。
     *
     * <p>入参记录必须已由应用层分配主键（{@code AuditIdGenerator}）——多行插入后无法逐条回读主键。
     */
    default List<SaveResult> saveAllIfAbsent(List<AuditLog> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<SaveResult> results = new java.util.ArrayList<>(entries.size());
        for (AuditLog entry : entries) {
            results.add(saveIfAbsent(entry));
        }
        return results;
    }

    /** 按主键查询（租户收敛由应用层负责）。 */
    Optional<AuditLog> findById(long id);

    /** 分页查询。 */
    List<AuditLog> findPage(Query query);

    /**
     * 与 {@link #findPage} 相同条件的总数，**最多统计到 {@code query.countCap()}**。
     *
     * <p>返回 {@code countCap} 即表示「已封顶、真实总数可能更大」，调用方按
     * {@code total >= countCap} 判定并向前端回执 {@code totalCapped}；
     * 实现上先取 {@code countCap} 行再计数，让超大结果集能提前停下，而不是扫完全部匹配行。
     */
    long count(Query query);

    /** 批量取租户名称；查询失败时返回空 Map（审计列表不能因为名称补全失败而整体不可用）。 */
    Map<Long, String> tenantNames(Collection<Long> tenantIds);

    /**
     * 批量取操作人展示信息（姓名/登录名），键为审计表的 {@code operator_id}。
     *
     * <p>审计表冗余了 {@code operator_id} 这个关联键，姓名/账号按它到账号表补全；
     * 与 {@link #tenantNames} 一样，查询失败只返回空 Map，不能因为补全失败让列表不可用。
     */
    Map<Long, OperatorDisplay> operatorDisplays(Collection<Long> operatorIds);
}
