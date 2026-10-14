package io.openware.common.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.openware.common.audit.infra.persistence.po.AuditLogPo;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogPo> {

    /**
     * 带上界的总数统计：最多证明「匹配行不少于 cap 行」。
     *
     * <p>采用原生 MyBatis 注解 SQL 的业务原因：MyBatis-Plus 的 {@code selectCount} 只能表达
     * {@code SELECT COUNT(*) FROM t WHERE ...}，无法在**聚合之前**加 {@code LIMIT}；
     * 而 {@code last("LIMIT n")} 会被拼到聚合结果之后（对 COUNT 无意义），达不到提前停止扫描的效果。
     * 这里用派生表先取 {@code cap} 行再计数，超大结果集可提前结束。
     *
     * <p>复用调用方传入的 {@code Wrapper}（{@code ${ew.customSqlSegment}}）而不重写过滤条件，
     * 保证统计口径与分页查询同源；wrapper 只承载 WHERE，不承载 ORDER BY。
     *
     * @param wrapper 与分页查询相同的过滤条件
     * @param cap     统计上界（&gt; 0）
     * @return 匹配行数，取值区间为 {@code [0, cap]}
     */
    @Select("SELECT COUNT(*) FROM (SELECT 1 FROM audit_log ${ew.customSqlSegment} LIMIT #{cap}) audit_count") /* tenant-agnostic: 租户谓词由 TenantLineInnerInterceptor 注入进 wrapper，不落在本行 */
    long countUpTo(@Param(Constants.WRAPPER) Wrapper<AuditLogPo> wrapper, @Param("cap") long cap);

    /**
     * 抢占幂等键：唯一键在台账上（主表要分区，无法保留原唯一键）。
     *
     * <p>用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} + 受影响行数判定，而不是
     * {@code INSERT IGNORE}（吞掉冲突无法区分首次/重复，H2 也不支持）或
     * {@code ON DUPLICATE KEY UPDATE}（affected-rows 语义在 MySQL 与 H2 上不一致）：
     * 顺序重试（绝大多数情况）走「插 0 行」，不依赖异常，两个方言行为一致。
     *
     * <p>并发同时抢同一个键时仍由主键兜底：败者抛 {@code DuplicateKeyException}，
     * 调用方按重复处理（见 {@code AuditLogRepositoryImpl.saveIfAbsent}）。
     *
     * <p>{@code audit_id} 在抢占时就写入：主键由应用侧生成（多行批插必须插入前就知道 ID），
     * 因此不需要「先插主表再回填台账」的第二步——每条记录少一次 UPDATE。
     *
     * <p>DC-11 的四个审计列在语句里**显式写死**，不吃列默认值：本台账的写入方只有审计服务自身
     * （系统），因此 {@code created_by} / {@code updated_by} = {@code 0}；台账只增不改，
     * {@code updated_at} 与 {@code created_at} 是同一时刻，取同一个入参而不是 DB 的
     * {@code CURRENT_TIMESTAMP(3)} 默认值——否则将来有人改了默认值，审计语义会静默漂移
     * （「谁写的、什么时候写的」不再由本语句决定）。
     *
     * @return 插入成功 1；键已存在 0
     */
    @Insert("INSERT INTO audit_idempotency "
            + "(tenant_id, idempotency_key, audit_id, created_by, created_at, updated_by, updated_at) "
            + "SELECT #{tenantId}, #{idempotencyKey}, #{auditId}, 0, #{createdAt}, 0, #{createdAt} FROM DUAL "
            + "WHERE NOT EXISTS (SELECT 1 FROM audit_idempotency WHERE tenant_id = #{tenantId} AND idempotency_key = #{idempotencyKey})")
    int claimIdempotencyKey(@Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey,
                            @Param("auditId") long auditId,
                            @Param("createdAt") java.time.LocalDateTime createdAt);

    /** 重复上报时取首次落库的记录 ID（未回填时返回 null，由调用方走恢复路径）。 */
    @Select("SELECT audit_id FROM audit_idempotency WHERE tenant_id = #{tenantId} "
            + "AND idempotency_key = #{idempotencyKey}")
    Long findClaimedAuditId(@Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey);

    /**
     * 恢复分支专用：台账有键但 {@code audit_id} 为空（历史版本「先插主表再回填」留下的残留）时补回填。
     *
     * <p>只补空值，不覆盖已有 ID：否则并发下会把别人的记录 ID 改掉。
     */
    @Update("UPDATE audit_idempotency SET audit_id = #{auditId} WHERE tenant_id = #{tenantId} "
            + "AND idempotency_key = #{idempotencyKey} AND audit_id IS NULL")
    int attachAuditIdIfAbsent(@Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey,
                              @Param("auditId") long auditId);

    /**
     * 一次多行插入审计主表（批量上报路径）：把 N 条逐行 INSERT 压成 1 条 SQL。
     *
     * <p>采用原生 MyBatis 注解 SQL 的业务原因：MyBatis-Plus 的 {@code insert} 只支持单行，
     * 而审计表是高频写入大表，批量上报时逐行往返是主要写入成本；这里用 {@code <foreach>} 生成
     * {@code VALUES (),(),...}，并且**显式包含 id**（应用侧生成）——因此插入后不需要回读主键。
     *
     * <p>不依赖 JDBC 批处理（{@code rewriteBatchedStatements}）：那需要 BatchExecutor，
     * 与 MyBatis-Plus 的默认 SIMPLE 执行器混用会绕过 Spring 事务的会话管理；多行字面量更直接。
     *
     * <p>DC-11 的审计列同样**显式列出**而不是靠列默认值（多行 VALUES 会显式写这些列，
     * 依赖默认值等于把审计语义交给 DDL 默认值）：写入方只有审计服务自身（系统），
     * {@code created_by} / {@code updated_by} = {@code 0}；本表只增不改，
     * {@code updated_at} 与 {@code created_at} 取同一个入参，保证两列恒等。
     *
     * @param entries 同一批待插入记录（调用方分片，单条 SQL 不超过 {@code MAX_BATCH_SIZE} 行）
     * @return 实际插入行数
     */
    @Insert("""
            <script>
            INSERT INTO audit_log
              (id, tenant_id, organization_id, store_id, operator_id, operator_name, operator_account,
               operator_type, action, action_label, resource_type, resource_id, resource_name, result,
               error_code, ip, user_agent, request_id, trace_id, source_service, idempotency_key,
               detail_json, occurred_at, created_by, created_at, updated_by, updated_at)
            VALUES
            <foreach collection="entries" item="entry" separator=",">
              (#{entry.id}, #{entry.tenantId}, #{entry.organizationId}, #{entry.storeId}, #{entry.operatorId},
               #{entry.operatorName}, #{entry.operatorAccount}, #{entry.operatorType}, #{entry.action},
               #{entry.actionLabel}, #{entry.resourceType}, #{entry.resourceId}, #{entry.resourceName},
               #{entry.result}, #{entry.errorCode}, #{entry.ip}, #{entry.userAgent}, #{entry.requestId},
               #{entry.traceId}, #{entry.sourceService}, #{entry.idempotencyKey}, #{entry.detailJson},
               #{entry.occurredAt}, 0, #{entry.createdAt}, 0, #{entry.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("entries") List<AuditLogPo> entries);
}
