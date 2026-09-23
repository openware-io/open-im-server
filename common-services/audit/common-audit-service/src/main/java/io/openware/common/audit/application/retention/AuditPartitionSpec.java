package io.openware.common.audit.application.retention;

import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * 审计分区命名与边界（纯计算，无副作用）：把「月份」与「分区名 / 上界字面量」互相转换。
 *
 * <p>分区名统一为 {@code p + YYYYMM}（如 {@code p202609}），与
 * {@code db/migration-audit/V2__audit_partitioned_table.sql} 里的命名一致；
 * 兜底分区固定为 {@code pmin} / {@code pmax}。
 *
 * <p>为什么边界要用字面量而不是参数绑定：{@code ALTER TABLE ... REORGANIZE PARTITION} 是 DDL，
 * 分区名与边界都无法用占位符。因此这里对两者都做**严格校验**（分区名必须匹配 {@code p\d{6}}、
 * 月份必须合法），从根上排除拼接注入——DDL 字符串只可能由「已校验的月份」构造出来。
 */
public final class AuditPartitionSpec {

    /** 兜底分区：早于最早月分区的时间。 */
    public static final String MIN_PARTITION = "pmin";

    /** 兜底分区：晚于最新月分区的时间（新月份未预建时数据落这里，非空即告警）。 */
    public static final String MAX_PARTITION = "pmax";

    /** 月分区名形态：p + 6 位年月。 */
    private static final Pattern MONTH_PARTITION = Pattern.compile("^p(\\d{4})(\\d{2})$");

    private AuditPartitionSpec() { }

    /** 月份 → 分区名（如 2026-09 → p202609）。 */
    public static String nameOf(YearMonth month) {
        return "p" + month.getYear() + String.format("%02d", month.getMonthValue());
    }

    /** 分区名 → 月份；不是月分区（pmin/pmax/异常命名）时返回 {@code null}。 */
    public static YearMonth monthOf(String partitionName) {
        if (partitionName == null) {
            return null;
        }
        var matcher = MONTH_PARTITION.matcher(partitionName.trim().toLowerCase(java.util.Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (RuntimeException exception) {
            // 形如 p202613 的非法月份（NumberFormatException / DateTimeException）：
            // 当作非月分区处理，绝不让它进入 DDL 拼接。
            return null;
        }
    }

    /**
     * 月分区的上界字面量（左闭右开）：该月上界 = 次月 1 号 00:00:00，例如
     * {@code 2026-09 → '2026-10-01 00:00:00'}。
     *
     * <p>分区边界按 UTC 解释（服务 JDBC 连接固定 {@code serverTimezone=UTC}，保留策略也按 UTC 月份计算）。
     */
    public static String upperBoundOf(YearMonth month) {
        return month.plusMonths(1).atDay(1) + " 00:00:00";
    }

    /** 月份 1 号 00:00:00 的时间戳（用于可查下限）。 */
    public static java.time.LocalDateTime firstMomentOf(YearMonth month) {
        return month.atDay(1).atStartOfDay();
    }
}
