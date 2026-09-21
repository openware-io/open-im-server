package com.gvchat.common.audit.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计保留策略的**副作用出口**（分区维护 + 幂等台账清理）。
 *
 * <p>抽成端口的原因：{@code ALTER TABLE ... REORGANIZE PARTITION} 是 MySQL 专有 DDL，
 * H2 不支持（单测跑不了），因此把「决策」与「执行」分开——决策在
 * {@code AuditRetentionPolicy}（纯逻辑、可单测），执行在本端口实现（真库验收走
 * {@code scripts/verify}）。这样保留策略的回归不依赖数据库方言。
 */
public interface AuditRetentionPort {

    /** 现有分区名（含 {@code pmin}/{@code pmax}）。 */
    List<String> partitionNames();

    /**
     * 追加一个月分区：把 {@code pmax} 拆成「新月份分区 + 新的 pmax」。
     *
     * <p>必须是 {@code REORGANIZE PARTITION pmax}——{@code MAXVALUE} 分区之后无法再 {@code ADD PARTITION}，
     * 且这样能保证**任何时间都写得进去**（新月份未预建时数据落 pmax，由巡检告警，而不是写入失败）。
     *
     * @param partitionName     月分区名（{@code p + YYYYMM}，由 {@code AuditPartitionSpec} 生成并已校验）
     * @param upperBoundExclusive 该月分区上界字面量（次月 1 号 00:00:00）
     */
    void addMonthPartition(String partitionName, String upperBoundExclusive);

    /** 指定分区的行数（巡检 {@code pmin}/{@code pmax} 是否非空）。 */
    long rowsInPartition(String partitionName);

    /**
     * 清理过期幂等键。
     *
     * <p>幂等只覆盖重试窗口，因此台账可以按时间清理；刻意不加 {@code LIMIT}（H2/MySQL 行为不一致，
     * 而清理依赖「按小时运行」让单次删除量自然有界）：首次启用后第一次运行可能删得较多，属预期。
     *
     * @return 实际删除行数
     */
    int deleteIdempotencyBefore(LocalDateTime cutoff);
}
