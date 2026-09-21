package com.gvchat.common.audit.infra.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审计分区维护（MySQL 专有 DDL/DML）：分区清单、追加月分区、分区行数、幂等台账清理。
 *
 * <p>这些语句**只对 MySQL 生效**（{@code information_schema.PARTITIONS} 与
 * {@code ALTER TABLE ... REORGANIZE PARTITION} 都不是可移植 SQL），因此不进 H2 单测，
 * 真库验收走 {@code scripts/verify/audit-partition-verify.sql}；单测用端口替身覆盖编排逻辑。
 */
@Mapper
public interface AuditPartitionMapper {

    /** 该表现有分区名（含 pmin/pmax 与月分区）。 */
    @Select("SELECT PARTITION_NAME FROM information_schema.PARTITIONS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iam_audit_log' "
            + "AND PARTITION_NAME IS NOT NULL ORDER BY PARTITION_ORDINAL_POSITION")
    List<String> partitionNames();

    /**
     * 追加一个月分区：把 {@code pmax} 拆成「新月份分区 + 新的 pmax」。
     *
     * <p>{@code MAXVALUE} 分区之后无法再 {@code ADD PARTITION}，只能 {@code REORGANIZE}；
     * 保留 pmax 是为了**任何时间都写得进去**（新月份未预建时落 pmax 并被巡检告警，而不是写入失败丢审计）。
     *
     * <p>分区名与上界由 {@code AuditPartitionSpec} 生成并严格校验（只可能是 {@code p\d{6}} 与
     * 合法日期字面量），因此这里的拼接不引入注入面——DDL 无法使用参数占位符。
     */
    @Update("ALTER TABLE iam_audit_log REORGANIZE PARTITION pmax INTO ("
            + "PARTITION ${partitionName} VALUES LESS THAN ('${upperBound}'), "
            + "PARTITION pmax VALUES LESS THAN (MAXVALUE))")
    void reorganizePmaxInto(@Param("partitionName") String partitionName, @Param("upperBound") String upperBound);

    /** 指定分区的行数（巡检 pmin/pmax 是否非空）。 */
    @Select("SELECT COUNT(*) FROM iam_audit_log PARTITION (${partitionName})")
    long countInPartition(@Param("partitionName") String partitionName);

    /** 清理过期幂等键（按 created_at，刻意不加 LIMIT：H2/MySQL 行为不一致，靠「按小时运行」有界）。 */
    @Delete("DELETE FROM iam_audit_idempotency WHERE created_at < #{cutoff}")
    int deleteIdempotencyBefore(@Param("cutoff") LocalDateTime cutoff);
}
