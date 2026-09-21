package com.gvchat.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.ReportTimeBuckets;
import com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 资源利用率（{@code /admin/reports/resources}）的**按次开台**口径回归测试。
 *
 * <h2>缺陷（ACK 环境实测）</h2>
 * 旧实现从 {@code res_occupation} 取数：开台写的是 {@code occupy(now, now + 24h)} 的固定 24 小时窗口，
 * 结台只把 {@code status} 置 RELEASED、不回缩 {@code end_at}，于是**每一行占用都恰好 24h**；
 * 报表又按 (门店, 包厢, 营业日) 把这些窗口求和 —— 实测包厢 1015 开出 4 条占用显示 96h、
 * 1017 开出 2 条显示 48h，而 1017 的真实消费只有 336 分 + 4 分。
 *
 * <p>本测试把修复后的口径钉死（与 {@code ReportMapper#selectResourceUtilization}、
 * {@code ResourceUtilizationReport.Row} 的注释一一对应）：
 * <ol>
 *   <li><b>一次开台一行</b>：同一包厢同一天多次开台 = 多行，各行只算自己那次时长（不是求和、不是 24h）；</li>
 *   <li><b>时长只来自会话</b>：{@code durationSeconds} 直接取会话侧算好的秒数；未结台记 0；</li>
 *   <li><b>turnoverCount</b> = 该次开台是否已完成（CLOSED=1），同行同包厢同营业日相加 = 当日翻台次数；</li>
 *   <li><b>turnoverRate</b> = 该包厢该营业日会话时长合计 ÷ 24h（比例值），分母按**营业日**而不是统计桶，
 *       同一包厢同营业日的每一行给同一个值。</li>
 * </ol>
 * 另有一条守门用例确保响应里**不再出现** {@code occupiedSeconds}，避免有人把占用窗口口径改回来。
 */
class ReportResourceUtilizationControllerTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2501L;
    private static final long ROOM_1017 = 1017L;
    private static final long ROOM_1015 = 1015L;
    /** 营业日可用时长（秒），与后端 {@code BUSINESS_DAY_AVAILABLE_SECONDS} 同口径。 */
    private static final long DAY_SECONDS = 24L * 60 * 60;

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ReportController controller = new ReportController(reportMapper);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** 核心：同一包厢同一营业日两次开台 → 两行，各算各的时长（不是 24h+24h，也不是求和）。 */
    @Test
    @DisplayName("一次开台一行：同包厢同日多次开台 = 多行，各算自己那次的时长")
    void oneRowPerSessionWithItsOwnDuration() {
        setTenant();
        // 包厢 1017 实际数据：336 分 + 4 分（2026-09-19 一个营业日内两次开台）
        stubSessions(List.of(
                session(9001L, ROOM_1017, "2026-09-19", "2026-09-19T10:00:00", "2026-09-19T15:36:00",
                        "CLOSED", 336 * 60L, 1L),
                session(9002L, ROOM_1017, "2026-09-19", "2026-09-19T16:00:00", "2026-09-19T16:04:00",
                        "CLOSED", 4 * 60L, 1L)));

        ReportController.ResourceUtilizationReport report = controller.resources(
                STORE_ID, "2026-09-01", "2026-09-30", null);

        assertEquals(2, report.rows().size(), "同一包厢同一天开台两次必须是两行，不能并成一行");
        ReportController.ResourceUtilizationReport.Row first = report.rows().get(0);
        ReportController.ResourceUtilizationReport.Row second = report.rows().get(1);
        assertEquals(9001L, first.sessionId());
        assertEquals(336 * 60L, first.durationSeconds(), "时长取该次会话自己的 opened_at → closed_at");
        assertEquals(4 * 60L, second.durationSeconds(), "第二次开台只算它自己的 4 分钟");
        assertEquals(20160L + 240L, first.durationSeconds() + second.durationSeconds());
        assertTrue(first.durationSeconds() != DAY_SECONDS && second.durationSeconds() != DAY_SECONDS,
                "绝不能出现占用窗口那个固定的 24h（86400 秒）");
        assertEquals(ROOM_1017, first.resourceId());
        assertEquals("K12", first.resourceName());
        assertEquals(LocalDate.of(2026, 9, 19), first.businessDate());
    }

    /** 未结台：closedAt 为空、时长记 0（不拿「现在」当结束时间），且不计入「已完成」。 */
    @Test
    @DisplayName("未结台：closedAt=null、durationSeconds=0、turnoverCount=0")
    void openSessionHasZeroDurationAndIsNotCompleted() {
        setTenant();
        stubSessions(List.of(
                session(8001L, ROOM_1017, "2026-09-19", "2026-09-19T20:00:00", null, "OPEN", 0L, 0L)));

        ReportController.ResourceUtilizationReport.Row row = controller.resources(
                STORE_ID, "2026-09-01", "2026-09-30", null).rows().get(0);

        assertNull(row.closedAt(), "未结台没有结台时间");
        assertEquals("OPEN", row.sessionStatus());
        assertEquals(0L, row.durationSeconds(), "未结台时长记 0，不拿查询时刻当结束时间");
        assertEquals(0L, row.turnoverCount(), "未结台不算「已完成」");
        assertEquals(0, row.turnoverRate().compareTo(BigDecimal.ZERO), "该包厢当日没有已发生的时长");
    }

    /** turnoverCount 是「该次开台是否已完成」：同一包厢同营业日各行相加 = 当日翻台次数。 */
    @Test
    @DisplayName("turnoverCount：已结台记 1、未结台记 0，同包厢同日各行相加 = 当日翻台次数")
    void turnoverCountMeansSessionCompleted() {
        setTenant();
        stubSessions(List.of(
                session(1L, ROOM_1015, "2026-09-19", "2026-09-19T10:00:00", "2026-09-19T11:00:00", "CLOSED", 3600L, 1L),
                session(2L, ROOM_1015, "2026-09-19", "2026-09-19T12:00:00", "2026-09-19T13:00:00", "CLOSED", 3600L, 1L),
                session(3L, ROOM_1015, "2026-09-19", "2026-09-19T14:00:00", null, "PAUSED", 0L, 0L),
                session(4L, ROOM_1015, "2026-09-19", "2026-09-19T15:00:00", "2026-09-19T16:30:00", "CLOSED", 5400L, 1L)));

        List<ReportController.ResourceUtilizationReport.Row> rows = controller.resources(
                STORE_ID, "2026-09-01", "2026-09-30", null).rows();

        assertEquals(4, rows.size(), "同包厢同日 4 次开台 = 4 行（不再是 1 行聚合）");
        assertEquals(3L, rows.stream().mapToLong(
                ReportController.ResourceUtilizationReport.Row::turnoverCount).sum(),
                "当日翻台次数 = 完成场次数（3 次结台），不是 RELEASED 占用行数");
        assertEquals(List.of(1L, 1L, 0L, 1L), rows.stream()
                .map(ReportController.ResourceUtilizationReport.Row::turnoverCount).toList());
    }

    /** 利用率 = 该包厢该营业日会话时长合计 ÷ 24h：同一包厢同营业日每行同值，跨营业日互不影响。 */
    @Test
    @DisplayName("turnoverRate = 该包厢该营业日会话时长合计 ÷ 24h（按营业日，不按统计桶）")
    void turnoverRateIsPerRoomPerBusinessDay() {
        setTenant();
        // 09-19：2.4h（8640s）→ 10%；09-20：4.8h（17280s）→ 20%；两天跨同一周（WEEK 桶相同）
        stubSessions(List.of(
                session(11L, ROOM_1017, "2026-09-19", "2026-09-19T10:00:00", "2026-09-19T11:24:00", "CLOSED", 5040L, 1L),
                session(12L, ROOM_1017, "2026-09-19", "2026-09-19T12:00:00", "2026-09-19T13:00:00", "CLOSED", 3600L, 1L),
                session(13L, ROOM_1017, "2026-09-20", "2026-09-20T10:00:00", "2026-09-20T14:48:00", "CLOSED", 17280L, 1L)));

        ReportController.ResourceUtilizationReport report = controller.resources(
                STORE_ID, "2026-09-01", "2026-09-30", "WEEK");

        List<ReportController.ResourceUtilizationReport.Row> rows = report.rows();
        assertEquals(3, rows.size());
        assertEquals(rows.get(0).bucket().start(), rows.get(2).bucket().start(),
                "两天在同一周桶里（粒度不影响行数）");
        assertEquals(0, rows.get(0).turnoverRate().compareTo(new BigDecimal("0.1")),
                "09-19 合计 8640s ÷ 86400s = 10%");
        assertEquals(0, rows.get(1).turnoverRate().compareTo(new BigDecimal("0.1")),
                "同一包厢同一营业日的每一行给同一个值");
        assertEquals(0, rows.get(2).turnoverRate().compareTo(new BigDecimal("0.2")),
                "09-20 单独按自己的营业日算（分母恒为一天 24h，不随粒度变大）");
        assertEquals("ROOM_DAY_SESSION_SUM_OVER_24H", report.utilizationBasis(), "口径标识必须回给使用方");
        assertEquals(5040L + 3600L + 17280L, rows.stream().mapToLong(
                ReportController.ResourceUtilizationReport.Row::durationSeconds).sum(),
                "逐行时长相加 = 三次开台各自的时长（不是 24h × 次数）");
    }

    /** 取数窗口与其它报表同源：营业日闭区间换算成存储值半开区间 + 共享平移量。 */
    @Test
    @DisplayName("取数窗口按营业日对齐，门店与粒度参数原样下推")
    void windowAndParamsArePushedDown() {
        setTenant();
        stubSessions(List.of());

        controller.resources(STORE_ID, "2026-09-01", "2026-09-30", "MONTH");

        LocalDateTime expectedFrom = ReportTimeBuckets.windowFrom(LocalDate.of(2026, 9, 1));
        LocalDateTime expectedTo = ReportTimeBuckets.windowToExclusive(LocalDate.of(2026, 9, 30));
        verify(reportMapper).selectResourceUtilization(eq(TENANT_ID), eq(STORE_ID), eq(expectedFrom), eq(expectedTo),
                eq(ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS));
    }

    /** 排序稳定：同桶按 门店 → 包厢 → 会话 ID，避免同一次查询两次结果行序不同。 */
    @Test
    @DisplayName("行序稳定：桶 → 门店 → 包厢 → 会话 ID")
    void rowsAreStablySorted() {
        setTenant();
        stubSessions(List.of(
                session(30L, ROOM_1015, "2026-09-19", "2026-09-19T18:00:00", "2026-09-19T19:00:00", "CLOSED", 3600L, 1L),
                session(20L, ROOM_1017, "2026-09-19", "2026-09-19T20:00:00", "2026-09-19T21:00:00", "CLOSED", 3600L, 1L),
                session(25L, ROOM_1017, "2026-09-19", "2026-09-19T10:00:00", "2026-09-19T11:00:00", "CLOSED", 3600L, 1L)));

        List<ReportController.ResourceUtilizationReport.Row> rows = controller.resources(
                STORE_ID, "2026-09-01", "2026-09-30", null).rows();

        // 包厢 1015 < 1017，先出 1015；同包厢内按会话 ID 升序（与前端「一次开台一行」的阅读顺序一致）
        assertEquals(List.of(30L, 20L, 25L), rows.stream()
                .map(ReportController.ResourceUtilizationReport.Row::sessionId).toList());
    }

    /** 旧字段必须消失：占用窗口求和出来的 occupiedSeconds 不得再出现在响应上。 */
    @Test
    @DisplayName("响应不再有 occupiedSeconds（占用窗口口径已废除）")
    void occupiedSecondsIsGone() {
        List<String> components = Arrays.stream(
                        ReportController.ResourceUtilizationReport.Row.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertTrue(components.containsAll(List.of("resourceId", "resourceName", "businessDate",
                        "openedAt", "closedAt", "durationSeconds", "turnoverCount", "turnoverRate")),
                "约定字段必须都在，实际: " + components);
        assertTrue(!components.contains("occupiedSeconds"),
                "occupiedSeconds 是占用窗口口径的遗留字段，必须删除，避免被改回去: " + components);
    }

    /** 没有租户上下文必须 401（不得跨租户取数）。 */
    @Test
    @DisplayName("缺少租户上下文 → 401")
    void missingTenantContextIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> controller.resources(STORE_ID, "2026-09-01", "2026-09-30", null));

        assertEquals(401, error.getStatus());
    }

    // —— 造数辅助 ——

    private static void setTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    private void stubSessions(List<Map<String, Object>> rows) {
        when(reportMapper.selectResourceUtilization(any(), any(), any(), any(), anyInt())).thenReturn(rows);
    }

    /**
     * 会话行（与 {@code selectResourceUtilization} 的列名一致）。
     *
     * <p>{@code duration_seconds} 在 DB 里由会话窗口算好，单测直接给定 —— 控制层不得再自己算时长。
     */
    private static Map<String, Object> session(Long sessionId, Long resourceId, String businessDate,
                                               String openedAt, String closedAt, String status,
                                               long durationSeconds, long turnoverCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", sessionId);
        row.put("store_id", STORE_ID);
        row.put("store_name", "A店");
        row.put("resource_id", resourceId);
        row.put("resource_name", "K12");
        row.put("business_date", businessDate);
        row.put("opened_at", openedAt);
        row.put("closed_at", closedAt);
        row.put("session_status", status);
        row.put("duration_seconds", durationSeconds);
        row.put("turnover_count", turnoverCount);
        return row;
    }
}
