package io.openware.common.audit.application.retention;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 审计保留策略（纯决策，不做任何 IO）：决定「预建哪些分区」「归档哪些月份」「列表可查下限」。
 *
 * <p>口径（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §5 批次 4）：
 * <ul>
 *   <li><b>热存 {@code hot-months} 个月</b>（默认 24）：包含当前月在内共 N 个自然月可查；</li>
 *   <li>早于热存窗口的月份进入归档清单并被移出可查范围（本阶段只标记，不投递、不删分区）；</li>
 *   <li><b>法律保留 {@code hold-periods}</b>（逗号分隔 {@code YYYY-MM}）：命中的月份既不归档也不删除，
 *       仍然可查——合规要求「涉及纠纷的月份不许动」；</li>
 *   <li><b>预建</b>当前月 + 未来 {@code pre-create-months} 个月，确保新写入不会落进 {@code pmax}。</li>
 * </ul>
 *
 * <p>全部按 <b>UTC</b> 计算自然月：服务 JDBC 连接固定 {@code serverTimezone=UTC}，
 * 分区边界也用 UTC 字面量，三者必须同口径，否则月初/月末会产生「落错分区」的错觉。
 */
@Slf4j
@Component
public class AuditRetentionPolicy {

    /** 列表可查下限的兜底：没有任何月份被归档时不设下限（返回 null）。 */
    private final int hotMonths;
    private final int preCreateMonths;
    private final Set<YearMonth> holdPeriods;

    public AuditRetentionPolicy(
            @Value("${audit.retention.hot-months:24}") int hotMonths,
            @Value("${audit.retention.pre-create-months:2}") int preCreateMonths,
            @Value("${audit.retention.hold-periods:}") String holdPeriods) {
        this.hotMonths = hotMonths > 0 ? hotMonths : 24;
        this.preCreateMonths = preCreateMonths >= 0 ? preCreateMonths : 2;
        this.holdPeriods = parsePeriods(holdPeriods);
    }

    /** 当前自然月（UTC）。 */
    public YearMonth currentMonth() {
        return YearMonth.now(ZoneOffset.UTC);
    }

    /** 热存窗口内最早的月份（含当前月，共 {@code hotMonths} 个月）。 */
    public YearMonth hotWindowStart(YearMonth now) {
        return now.minusMonths(hotMonths - 1L);
    }

    /**
     * 需要预建的分区月份：当前月与未来 {@code preCreateMonths} 个月中**尚不存在**的那些。
     *
     * @param existingPartitionNames 现有分区名（{@code information_schema.PARTITIONS} 读出）
     */
    public List<YearMonth> monthsToPreCreate(Collection<String> existingPartitionNames, YearMonth now) {
        Set<YearMonth> existing = new LinkedHashSet<>();
        if (existingPartitionNames != null) {
            for (String name : existingPartitionNames) {
                YearMonth month = AuditPartitionSpec.monthOf(name);
                if (month != null) {
                    existing.add(month);
                }
            }
        }
        List<YearMonth> missing = new ArrayList<>();
        for (int offset = 0; offset <= preCreateMonths; offset++) {
            YearMonth month = now.plusMonths(offset);
            if (!existing.contains(month)) {
                missing.add(month);
            }
        }
        return missing;
    }

    /**
     * 归档决策：把「早于热存窗口」的**已有月分区**分成两类。
     *
     * <p>候选集刻意由「现有分区」而不是「从 2000 年穷举月份」推导：一个从未建过分区的月份不可能有数据，
     * 穷举只会产生几百条空清单行；同时这也让首次运行的开销与真实数据量成正比。
     *
     * @param existingPartitionMonths 现有月分区对应的月份（由 {@code information_schema.PARTITIONS} 解析）
     * @param archivedMonths          已在清单里标记 {@code ARCHIVED} 的月份（幂等：不重复处理）
     */
    public RetentionDecision decide(YearMonth now, Collection<YearMonth> existingPartitionMonths,
                                    Collection<YearMonth> archivedMonths) {
        Set<YearMonth> archived = archivedMonths == null ? Set.of() : new LinkedHashSet<>(archivedMonths);
        YearMonth windowStart = hotWindowStart(now);
        java.util.TreeSet<YearMonth> oldMonths = new java.util.TreeSet<>();
        if (existingPartitionMonths != null) {
            for (YearMonth month : existingPartitionMonths) {
                if (month != null && month.isBefore(windowStart)) {
                    oldMonths.add(month);
                }
            }
        }
        List<YearMonth> toHold = new ArrayList<>();
        List<YearMonth> toArchive = new ArrayList<>();
        for (YearMonth month : oldMonths) {
            if (isHeld(month)) {
                // 法律保留：不归档也不删除，只登记保留意图（升序，便于巡检阅读）。
                toHold.add(month);
            } else if (!archived.contains(month)) {
                toArchive.add(month);
            }
        }
        return new RetentionDecision(toArchive, toHold);
    }

    /** 归档决策结果：升序排列，先处理最老的月份。 */
    public record RetentionDecision(List<YearMonth> toArchive, List<YearMonth> toHold) { }

    /** 该月份是否处于法律保留（合规要求：不归档、不删除、仍可查）。 */
    public boolean isHeld(YearMonth month) {
        return holdPeriods.contains(month);
    }

    /**
     * 列表可查下限 = 最新已归档月份的**次月 1 号**；没有任何归档时返回 {@code null}（不设下限）。
     *
     * <p>为什么由「清单」而不是「热存窗口」推导：归档是显式动作，标记为 ARCHIVED 的月份才移出可查范围，
     * 避免出现「策略算出来该排除、但清单里还没记」的中间态被直接隐藏。
     *
     * <p>已知边界：法律保留的月份若**早于**该下限，仍会被排除在列表之外（数据还在库里，可按 ID 查详情）。
     * 保留月通常是最新的月份，落在下限之上的概率极低；运维巡检在发现这种组合时会告警（见 retention 服务）。
     */
    public java.time.LocalDateTime queryFloor(Collection<YearMonth> archivedMonths) {
        if (archivedMonths == null || archivedMonths.isEmpty()) {
            return null;
        }
        YearMonth latest = archivedMonths.stream().max(YearMonth::compareTo).orElse(null);
        return latest == null ? null : AuditPartitionSpec.firstMomentOf(latest.plusMonths(1));
    }

    /** 幂等台账的保留截止时间：早于它的幂等键可以清理（幂等只需覆盖重试窗口）。 */
    public java.time.LocalDateTime idempotencyCutoff(java.time.LocalDateTime now, int retentionDays) {
        return now.minusDays(Math.max(1, retentionDays));
    }

    /** 解析 {@code YYYY-MM,YYYY-MM} 形式的法律保留月份；非法片段直接忽略并计入日志（不阻断启动）。 */
    private static Set<YearMonth> parsePeriods(String raw) {
        Set<YearMonth> periods = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return periods;
        }
        for (String piece : raw.split(",")) {
            String value = piece.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                periods.add(YearMonth.parse(value));
            } catch (RuntimeException exception) {
                log.warn("忽略非法的法律保留月份配置（应为 YYYY-MM）: {}", value);
            }
        }
        return periods;
    }

    /** 供日志/巡检展示当前生效的策略。 */
    public String describe() {
        return "hotMonths=" + hotMonths + ", preCreateMonths=" + preCreateMonths + ", holdPeriods=" + holdPeriods;
    }
}
