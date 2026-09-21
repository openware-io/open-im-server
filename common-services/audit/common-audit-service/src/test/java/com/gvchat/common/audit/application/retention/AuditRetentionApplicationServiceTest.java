package com.gvchat.common.audit.application.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.audit.domain.repository.AuditArchiveManifestRepository;
import com.gvchat.common.audit.domain.repository.AuditRetentionPort;
import com.gvchat.infrastructure.audit.AuditClient;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 保留编排回归（端口替身，不依赖数据库）：预建 → 兜底巡检 → 归档清单登记 → 幂等台账清理。
 *
 * <p>重点钉住三件「静默就会出事」的事：
 * <ol>
 *   <li>缺哪些分区就补哪些，且**始终通过 REORGANIZE pmax** 追加（新数据永远不会因缺分区写失败）；</li>
 *   <li>超出热存窗口的已有月份登记 {@code ARCHIVED}（本阶段**不投递、不删分区**）并自留一条审计；</li>
 *   <li>法律保留月份登记 {@code HELD} 且不归档；幂等台账按配置天数清理。</li>
 * </ol>
 */
class AuditRetentionApplicationServiceTest {

    private static final YearMonth CURRENT =
            new AuditRetentionPolicy(24, 2, "").currentMonth();

    private AuditRetentionPort port;
    private AuditArchiveManifestRepository manifest;
    private AuditClient auditClient;

    /** 记录所有追加分区的调用，便于断言「补了哪几个月、上界是什么」。 */
    private final List<String> addedPartitions = new ArrayList<>();

    /** 现有月分区：当前月与未来两个月已存在；再给两个「很旧」的月分区用于归档场景。 */
    private final List<String> partitions = new ArrayList<>(List.of(
            "pmin", "pmax",
            AuditPartitionSpec.nameOf(CURRENT),
            AuditPartitionSpec.nameOf(CURRENT.plusMonths(1)),
            AuditPartitionSpec.nameOf(CURRENT.plusMonths(2)),
            AuditPartitionSpec.nameOf(CURRENT.minusMonths(24)),
            AuditPartitionSpec.nameOf(CURRENT.minusMonths(25))));

    @BeforeEach
    void setUp() {
        port = mock(AuditRetentionPort.class);
        manifest = mock(AuditArchiveManifestRepository.class);
        auditClient = mock(AuditClient.class);
        addedPartitions.clear();
        when(port.partitionNames()).thenReturn(partitions);
        when(manifest.archivedMonths()).thenReturn(Set.of());
        doAnswer(invocation -> {
            addedPartitions.add(invocation.getArgument(0));
            return null;
        }).when(port).addMonthPartition(anyString(), anyString());
    }

    private AuditRetentionApplicationService service(int hotMonths, String holds, int idempotencyDays) {
        return new AuditRetentionApplicationService(new AuditRetentionPolicy(hotMonths, 2, holds), port, manifest,
                auditClient, idempotencyDays);
    }

    @Test
    void existingPartitionsAreNotRebuilt() {
        service(24, "", 7).runOnce();

        assertTrue(addedPartitions.isEmpty(), "分区都已存在时不应产生任何 DDL");
    }

    @Test
    void missingFutureMonthPartitionIsAddedWithExclusiveUpperBound() {
        // 预建范围 = 当前月 + 未来 2 个月；这里只让「当前月、下月」存在，缺的是下下月。
        YearMonth missing = CURRENT.plusMonths(2);
        when(port.partitionNames()).thenReturn(List.of("pmin", "pmax", AuditPartitionSpec.nameOf(CURRENT),
                AuditPartitionSpec.nameOf(CURRENT.plusMonths(1))));

        service(24, "", 7).runOnce();

        assertEquals(List.of(AuditPartitionSpec.nameOf(missing)), addedPartitions);
        verify(port).addMonthPartition(AuditPartitionSpec.nameOf(missing),
                AuditPartitionSpec.upperBoundOf(missing));
        verify(auditClient, atLeastOnce()).recordAsync(any());
    }

    /** pmax 非空说明预建没跟上：这些记录不会参与保留策略，必须被巡检读出来（服务里按 WARN 告警）。 */
    @Test
    void fallbackPartitionsAreInspected() {
        when(port.rowsInPartition(AuditPartitionSpec.MAX_PARTITION)).thenReturn(5L);
        when(port.rowsInPartition(AuditPartitionSpec.MIN_PARTITION)).thenReturn(2L);

        service(24, "", 7).runOnce();

        verify(port).rowsInPartition(AuditPartitionSpec.MAX_PARTITION);
        verify(port).rowsInPartition(AuditPartitionSpec.MIN_PARTITION);
    }

    /** 超出热存窗口的已有月份 → 登记 ARCHIVED；本阶段不投递（objectPath 为空）、没有任何删分区能力。 */
    @Test
    void monthsBeyondHotWindowAreArchivedWithoutDroppingPartitions() {
        service(24, "", 7).runOnce();

        ArgumentCaptor<YearMonth> archived = ArgumentCaptor.forClass(YearMonth.class);
        verify(manifest, org.mockito.Mockito.times(2)).markArchived(archived.capture(), anyString(), anyLong(), any());
        assertEquals(List.of(CURRENT.minusMonths(25), CURRENT.minusMonths(24)), archived.getAllValues(),
                "两个超窗月份按升序登记；窗口内的月份不登记");
        verify(manifest, never()).markHeld(any(), anyString(), any());
    }

    @Test
    void heldMonthsAreMarkedHeldAndNeverArchived() {
        YearMonth held = CURRENT.minusMonths(24);

        service(24, held.toString(), 7).runOnce();

        verify(manifest).markHeld(eq(held), eq(AuditPartitionSpec.nameOf(held)), any());
        ArgumentCaptor<YearMonth> archived = ArgumentCaptor.forClass(YearMonth.class);
        verify(manifest, atLeastOnce()).markArchived(archived.capture(), anyString(), anyLong(), any());
        assertTrue(archived.getAllValues().stream().noneMatch(held::equals), "法律保留月份绝不能进归档清单");
    }

    @Test
    void idempotencyLedgerIsPurgedWithConfiguredRetention() {
        service(24, "", 7).runOnce();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(port).deleteIdempotencyBefore(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(6)), "截止时间应是 7 天前");
    }

    /** 归档幂等：已在清单里的月份不再重复登记（任务按小时间隔运行）。 */
    @Test
    void archivedMonthsAreNotProcessedTwice() {
        YearMonth already = CURRENT.minusMonths(24);
        when(manifest.archivedMonths()).thenReturn(Set.of(already));

        service(24, "", 7).runOnce();

        ArgumentCaptor<YearMonth> archived = ArgumentCaptor.forClass(YearMonth.class);
        verify(manifest, atLeastOnce()).markArchived(archived.capture(), anyString(), anyLong(), any());
        assertTrue(archived.getAllValues().stream().noneMatch(already::equals));
    }

    /** 保留窗口调大后，原本超窗的月份重新落入热存范围（不再归档）——策略改动必须立刻生效。 */
    @Test
    void wideningHotWindowStopsArchivingThoseMonths() {
        service(60, "", 7).runOnce();

        verify(manifest, never()).markArchived(any(), anyString(), anyLong(), any());
    }
}
