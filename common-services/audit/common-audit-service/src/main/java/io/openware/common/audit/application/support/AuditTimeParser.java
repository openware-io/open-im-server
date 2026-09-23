package io.openware.common.audit.application.support;

import io.openware.common.exception.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 审计时间参数解析：支持「{@code yyyy-MM-dd HH:mm:ss}」「ISO-8601（含偏移量）」「epoch 毫秒/秒」三种口径。
 *
 * <p>审计查询是给运营/财务用的排查工具，前端与脚本都会直接拼时间参数，必须一次都说清楚：
 * 带偏移量的 ISO 串按 {@link ZoneId#systemDefault()} 归一到服务端本地时间（与表中 {@code created_at}
 * 的写入口径一致），不带时区的串按原样解释，非法值一律 400，不能落到 SQL 或抛 500。
 */
public final class AuditTimeParser {

    private static final DateTimeFormatter SPACE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private AuditTimeParser() {}

    /** 解析为本地时间；{@code null}/空串返回 {@code null}，非法格式抛 400。 */
    public static LocalDateTime parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("\\d{10}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(value)), ZoneId.systemDefault());
        }
        if (value.matches("\\d{13}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
        }
        if (value.contains("T")) {
            return parseIso(value, field);
        }
        try {
            return LocalDateTime.parse(value, SPACE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            throw invalid(field, raw);
        }
    }

    /** 仅日期（{@code yyyy-MM-dd}）按当天 00:00 解释，用于「到今天为止」这类区间参数。 */
    public static LocalDateTime parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(value).atStartOfDay();
        }
        return parse(value, field);
    }

    private static LocalDateTime parseIso(String value, String field) {
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(value).toInstant(), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            // 继续尝试不带偏移量的形态
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间形态
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            throw invalid(field, value);
        }
    }

    private static ApiException invalid(String field, String raw) {
        return new ApiException(400, "INVALID_ARGUMENT", "时间参数 " + field + " 格式非法: " + raw);
    }
}
