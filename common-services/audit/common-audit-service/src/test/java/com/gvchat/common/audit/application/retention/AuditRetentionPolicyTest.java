package com.gvchat.common.audit.application.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 保留策略决策回归（纯逻辑，不依赖数据库）。
 *
 * <p>钉住四条口径：热存窗口边界、预建范围、法律保留的豁免、以及「可查下限由归档清单推导」
 * （而不是由热存窗口推导——否则会出现「策略算出来该排除、但清单里还没记」的中间态被直接隐藏）。
 */
class AuditRetentionPolicyTest {

    /** 默认策略：热存 24 个月、预建 2 个月、无法律保留。 */
    private final AuditRetentionPolicy policy = new AuditRetentionPolicy(24, 2, "");

    private static final YearMonth NOW = YearMonth.of(2026, 9);

    /** 热存 24 个月（含当前月）→ 窗口起点是 2024-10，2024-09 及更早应进入归档。 */
    @Test
    void hotWindowKeepsConfiguredMonthsIncludingCurrent() {
        assertEquals(YearMonth.of(2024, 10), policy.hotWindowStart(NOW));

        AuditRetentionPolicy.RetentionDecision decision = policy.decide(NOW,
                List.of(YearMonth.of(2026, 9), YearMonth.of(2024, 10), YearMonth.of(2024, 9),
                        YearMonth.of(2023, 5)),
                Set.of());

        assertEquals(List.of(YearMonth.of(2023, 5), YearMonth.of(2024, 9)), decision.toArchive(),
                "只有早于窗口起点的**已有分区**才归档，窗口内月份不归档，且升序返回");
        assertTrue(decision.toHold().isEmpty());
    }

    @Test
    void alreadyArchivedMonthsAreNotReturnedAgain() {
        AuditRetentionPolicy.RetentionDecision decision = policy.decide(NOW,
                List.of(YearMonth.of(2024, 9), YearMonth.of(2024, 8)), Set.of(YearMonth.of(2024, 9)));

        assertEquals(List.of(YearMonth.of(2024, 8)), decision.toArchive(), "已归档月份必须幂等跳过");
    }

    /** 法律保留的月份既不归档也不删除，仍然可查（只登记保留意图供巡检查看）。 */
    @Test
    void heldMonthsAreExemptFromArchiving() {
        AuditRetentionPolicy holding = new AuditRetentionPolicy(24, 2, "2024-09, 2024-08");

        AuditRetentionPolicy.RetentionDecision decision = holding.decide(NOW,
                List.of(YearMonth.of(2024, 9), YearMonth.of(2024, 8), YearMonth.of(2024, 7)), Set.of());

        assertTrue(holding.isHeld(YearMonth.of(2024, 9)));
        assertEquals(List.of(YearMonth.of(2024, 8), YearMonth.of(2024, 9)), decision.toHold());
        assertEquals(List.of(YearMonth.of(2024, 7)), decision.toArchive(), "保留月份之前的仍要归档");
    }

    /** 从未建过分区的月份不产生空清单行：候选集由现有分区推导，而不是穷举历史月份。 */
    @Test
    void monthsWithoutPartitionsAreNotInventoried() {
        AuditRetentionPolicy.RetentionDecision decision = policy.decide(NOW, List.of(), Set.of());

        assertTrue(decision.toArchive().isEmpty());
        assertTrue(decision.toHold().isEmpty());
    }

    /** 预建：当前月 + 未来 2 个月都要存在，已有的不重复建。 */
    @Test
    void missingPartitionsCoverCurrentAndFutureMonths() {
        List<YearMonth> missing = policy.monthsToPreCreate(
                List.of("pmin", "pmax", "p202609", "p202610"), NOW);

        assertEquals(List.of(YearMonth.of(2026, 11)), missing, "只补缺失的 2026-11（pmin/pmax 不参与）");
    }

    @Test
    void malformedPartitionNamesAreIgnoredInsteadOfBreakingPreCreate() {
        List<YearMonth> missing = policy.monthsToPreCreate(
                List.of("pmin", "pmax", "p202613", "not-a-partition"), NOW);

        assertEquals(3, missing.size(), "非法分区名按不存在处理（当前月 + 未来两个月都要建）");
    }

    /** 可查下限 = 最新已归档月份的次月 1 号；没有归档时不设下限。 */
    @Test
    void queryFloorComesFromArchiveManifest() {
        assertNull(policy.queryFloor(Set.of()), "没有任何归档时不设下限");

        LocalDateTime floor = policy.queryFloor(Set.of(YearMonth.of(2024, 9), YearMonth.of(2024, 7)));

        assertEquals(LocalDateTime.of(2024, 10, 1, 0, 0), floor, "取最新归档月份的次月 1 号");
    }

    /** 分区名与月份的互相转换必须可逆，边界按 UTC 字面量构造。 */
    @Test
    void partitionNamingRoundTripsAndBoundsAreExclusive() {
        assertEquals("p202609", AuditPartitionSpec.nameOf(YearMonth.of(2026, 9)));
        assertEquals(YearMonth.of(2026, 9), AuditPartitionSpec.monthOf("p202609"));
        assertNull(AuditPartitionSpec.monthOf("pmax"));
        assertNull(AuditPartitionSpec.monthOf("p2026"));
        assertEquals("2026-10-01 00:00:00", AuditPartitionSpec.upperBoundOf(YearMonth.of(2026, 9)));
    }

    @Test
    void idempotencyCutoffUsesConfiguredDays() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);

        assertEquals(LocalDateTime.of(2026, 9, 12, 12, 0), policy.idempotencyCutoff(now, 7));
    }
}
