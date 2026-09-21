package com.gvchat.common.audit.application.retention;

import com.gvchat.common.audit.domain.repository.AuditArchiveManifestRepository;
import com.gvchat.common.audit.domain.repository.AuditRetentionPort;
import com.gvchat.infrastructure.audit.AuditClient;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审计保留编排：预建分区 → 兜底分区巡检 → 归档清单登记 → 幂等台账清理。
 *
 * <p>方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §5 批次 4。三条不可退让的口径：
 * <ol>
 *   <li><b>预建与兜底</b>：始终保留 {@code pmax}，新月份未预建时数据落 {@code pmax} 而不是写入失败；
 *       {@code pmin}/{@code pmax} 非空即告警——它们非空意味着「有记录落在永不参与保留策略的分区里」，
 *       是审计口径出问题的信号，绝不能静默；</li>
 *   <li><b>本阶段只登记清单</b>：超出热存窗口的月份标记 {@code ARCHIVED} 并移出可查范围，
 *       <b>不投递对象存储、不 DROP PARTITION</b>；投递与删除落地时的硬约束是「清单存在 + 校验和复核通过」；</li>
 *   <li><b>法律保留</b>：命中 {@code hold-periods} 的月份只登记 {@code HELD}，不动数据、不影响可查范围。</li>
 * </ol>
 *
 * <p>归档与预建都是保留策略的状态变更，按合规要求**自留审计**（{@code audit.retention.*}）。
 */
@Slf4j
@Service
public class AuditRetentionApplicationService {

    /** 自留审计动作码：归档清单登记。 */
    static final String ACTION_ARCHIVE = "audit.retention.archive";

    /** 自留审计动作码：月分区预建。 */
    static final String ACTION_PARTITION_ADD = "audit.retention.partition.add";

    private final AuditRetentionPolicy policy;
    private final AuditRetentionPort port;
    private final AuditArchiveManifestRepository manifest;
    private final AuditClient auditClient;
    private final int idempotencyRetentionDays;

    public AuditRetentionApplicationService(AuditRetentionPolicy policy, AuditRetentionPort port,
                                            AuditArchiveManifestRepository manifest, AuditClient auditClient,
                                            @Value("${audit.retention.idempotency-days:7}")
                                            int idempotencyRetentionDays) {
        this.policy = policy;
        this.port = port;
        this.manifest = manifest;
        this.auditClient = auditClient;
        this.idempotencyRetentionDays = idempotencyRetentionDays;
    }

    /** 执行一轮保留策略（幂等：可重复运行，按小时调度）。 */
    public void runOnce() {
        YearMonth now = policy.currentMonth();
        preCreatePartitions(now);
        inspectFallbackPartitions();
        registerArchiveManifest(now);
        purgeIdempotencyLedger();
    }

    /**
     * 预建当前月与未来若干月的分区。
     *
     * <p>预建失败**不抛出**：保留任务失败不能反过来影响审计写入（写入靠 pmax 兜底仍然成功），
     * 但必须留下 ERROR 级日志与告警，由运维补齐分区后重跑。
     */
    private void preCreatePartitions(YearMonth now) {
        List<YearMonth> missing = policy.monthsToPreCreate(port.partitionNames(), now);
        for (YearMonth month : missing) {
            String name = AuditPartitionSpec.nameOf(month);
            String upperBound = AuditPartitionSpec.upperBoundOf(month);
            try {
                port.addMonthPartition(name, upperBound);
                log.info("审计分区已预建: partition={}, upperBoundExclusive={}", name, upperBound);
                auditClient.recordAsync(AuditClient.AuditRecord.builder()
                        .action(ACTION_PARTITION_ADD)
                        .resourceType("audit_partition").resourceId(name)
                        .detailJson("{\"period\":\"" + month + "\",\"upperBound\":\"" + upperBound + "\"}")
                        .build());
            } catch (RuntimeException failure) {
                log.error("审计分区预建失败（新数据将落 pmax，需尽快补齐）: partition={}, cause={}",
                        name, failure.getMessage(), failure);
            }
        }
    }

    /**
     * 兜底分区巡检：{@code pmin}/{@code pmax} 非空即告警。
     *
     * <p>两者正常恒为空：{@code pmin} 非空说明有早于最早月分区的时间被写入（脏时间或回填越界），
     * {@code pmax} 非空说明预建没跟上。两种情况下这些记录都**不会参与保留策略**，
     * 属于审计口径问题，必须让人看见而不是等它悄悄堆积。
     */
    private void inspectFallbackPartitions() {
        long minRows = port.rowsInPartition(AuditPartitionSpec.MIN_PARTITION);
        long maxRows = port.rowsInPartition(AuditPartitionSpec.MAX_PARTITION);
        if (minRows > 0) {
            log.warn("审计兜底分区 pmin 非空（{} 行）：存在早于最早月分区的时间，"
                    + "这些记录不会参与保留策略，请核对写入时间与回填区间", minRows);
        }
        if (maxRows > 0) {
            log.warn("审计兜底分区 pmax 非空（{} 行）：月分区预建未跟上，"
                    + "这些记录不会参与保留策略，请立即补齐分区", maxRows);
        }
    }

    /** 超出热存窗口的已有月份：登记 {@code ARCHIVED}（本阶段不投递、不删分区）；法律保留月份登记 {@code HELD}。 */
    private void registerArchiveManifest(YearMonth now) {
        List<YearMonth> existingMonths = port.partitionNames().stream()
                .map(AuditPartitionSpec::monthOf)
                .filter(java.util.Objects::nonNull)
                .toList();
        AuditRetentionPolicy.RetentionDecision decision =
                policy.decide(now, existingMonths, manifest.archivedMonths());
        for (YearMonth month : decision.toHold()) {
            manifest.markHeld(month, AuditPartitionSpec.nameOf(month), null);
            log.info("审计月份命中法律保留，不归档也不删除: period={}", month);
        }
        for (YearMonth month : decision.toArchive()) {
            String partitionName = AuditPartitionSpec.nameOf(month);
            long rows = 0L;
            try {
                rows = port.rowsInPartition(partitionName);
            } catch (RuntimeException failure) {
                // 读行数失败不阻断整轮任务：清单仍要登记（行数按 0），否则该月永远排不进归档流程。
                log.debug("读取审计分区行数失败: partition={}, cause={}", partitionName, failure.getMessage());
            }
            manifest.markArchived(month, partitionName, rows, null);
            log.info("审计月份已登记归档清单（本阶段不投递、不删分区）: period={}, partition={}, rows={}",
                    month, partitionName, rows);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action(ACTION_ARCHIVE)
                    .resourceType("audit_partition").resourceId(partitionName)
                    .detailJson("{\"period\":\"" + month + "\",\"rows\":" + rows + ",\"delivered\":false}")
                    .build());
        }
    }

    /**
     * 清理过期幂等键。
     *
     * <p>幂等只覆盖「重试窗口」，因此台账按 {@code idempotency-days} 清理；台账是独立小表，
     * 清理不会触碰审计主表（审计记录只增不改，任何物理删除都只发生在分区粒度上）。
     */
    private void purgeIdempotencyLedger() {
        LocalDateTime cutoff = policy.idempotencyCutoff(LocalDateTime.now(), idempotencyRetentionDays);
        try {
            int purged = port.deleteIdempotencyBefore(cutoff);
            if (purged > 0) {
                log.info("清理过期审计幂等键: purged={}, cutoff={}", purged, cutoff);
            }
        } catch (RuntimeException failure) {
            log.warn("清理过期审计幂等键失败（不影响审计写入）: cutoff={}, cause={}", cutoff, failure.getMessage());
        }
    }
}
