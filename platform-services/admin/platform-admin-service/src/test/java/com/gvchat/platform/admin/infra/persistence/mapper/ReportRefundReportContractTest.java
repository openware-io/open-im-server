package com.gvchat.platform.admin.infra.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.admin.api.controller.ReportController;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 退款报表口径回归：{@code ReportMapper#selectRefunds} 必须按 {@code pay_refund} 的**真实终态**过滤。
 *
 * <p>背景（本条是修真 bug）：历史 SQL 写的是 {@code r.status = 'SUCCEEDED'}，而 {@code pay_refund}
 * 的状态机只有 {@code PENDING / APPROVED / REJECTED / REFUNDED}
 * （{@code common-payment-service RefundApplicationService.java:25-28}，DDL 见
 * {@code V1__pay_baseline.sql:23-32} + {@code V7__pay_refund_status_pending.sql}），
 * 没有任何一行会是 SUCCEEDED —— 退款报表因此恒为空行。
 *
 * <p>admin 服务没有数据库测试夹具（{@code src/test/resources} 为空，报表 SQL 不在单测里执行），
 * 因此这里用两条互补的断言守住口径：
 * <ol>
 *   <li>读取 mapper 注解里的 SQL 文本，断言过滤条件是终态 {@code REFUNDED}、且不含 SUCCEEDED
 *       与其它非终态（待审批/已通过/已驳回）—— 这是「已拒绝/待审批不计数」的强制点；</li>
 *   <li>按 mapper 契约造一条 REFUNDED 退款行，驱动 ReportController 聚合，断言
 *       报表行/金额出现且币种取自原订单快照。</li>
 * </ol>
 */
class ReportRefundReportContractTest {

    private static final long TENANT_ID = 1001L;
    private static final long STORE_ID = 2501L;

    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final ReportController controller = new ReportController(reportMapper);

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** 退款口径 = 已登记退还的终态；绝不能再写成收款态的 SUCCEEDED。 */
    @Test
    void refundReportOnlyCountsTerminalRefundedStatus() throws Exception {
        String sql = selectSql("selectRefunds");

        assertTrue(sql.contains("r.status = 'REFUNDED'"),
                "pay_refund 的终态是 REFUNDED（RefundApplicationService.java:25-28），退款报表必须按它过滤");
        assertFalse(sql.contains("SUCCEEDED"),
                "pay_refund 没有 SUCCEEDED 状态（那是 pay_intent/pay_transaction 的收款态），过滤它会让报表恒为空");
        assertFalse(sql.contains("'PENDING'"), "待审批的退款不能计入已退款金额");
        assertFalse(sql.contains("'APPROVED'"), "审批通过但未登记线下退款的不能计入已退款金额");
        assertFalse(sql.contains("'REJECTED'"), "已驳回的退款不能计入已退款金额");
        assertTrue(sql.contains("COALESCE(r.approved_amount, 0)"), "金额仍以审批金额 approved_amount 为准");
        assertTrue(sql.contains("JOIN ord_order o"), "退款币种必须回查原订单快照，不能按当前租户设置解释历史");
        assertTrue(sql.contains("r.tenant_id = #{tenantId}") && sql.contains("r.created_at >= #{from}"),
                "报表必须保持租户隔离与 [from, to) 期间过滤");
    }

    /** 经营报表：只有退款（当期没有对应订单行）时也要出行，金额与币种来自退款记录。 */
    @Test
    void operationsReportShowsRefundRowWithOrderCurrencySnapshot() {
        setTenant();
        when(reportMapper.selectOperations(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(refund("2026-01-15", "CNY", 2, "1500")));

        ReportController.OperationsReport report = controller.operations(
                STORE_ID, "2026-01-01", "2026-01-31", null);

        assertEquals(1, report.rows().size(), "有退款就必须出一行（退款行不能被订单行为空吞掉）");
        ReportController.OperationsReport.Row row = report.rows().get(0);
        assertEquals("CNY", row.currencyCode(), "币种取原订单快照");
        assertEquals(0, row.orderCount());
        assertEquals(0, row.refundAmount().compareTo(new BigDecimal("1500")));
    }

    /** 支付报表：同一口径（终态 REFUNDED、原订单币种）。 */
    @Test
    void paymentsReportShowsRefundRowWithOrderCurrencySnapshot() {
        setTenant();
        when(reportMapper.selectCollections(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reportMapper.selectRefunds(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(refund("2026-01-15", "USD", 1, "700")));

        ReportController.PaymentsReport report = controller.payments(
                STORE_ID, "2026-01-01", "2026-01-31", null);

        assertEquals(1, report.rows().size());
        ReportController.PaymentsReport.Row row = report.rows().get(0);
        assertEquals("USD", row.currencyCode());
        assertEquals(1, row.refundCount());
        assertEquals(0, row.refundAmount().compareTo(new BigDecimal("700")));
    }

    /** 退款 SQL 的切日必须与营业日口径一致（否则退款行会落到错误的桶里）。 */
    @Test
    void refundSqlUsesBusinessDayShift() throws Exception {
        String sql = selectSql("selectRefunds");
        assertTrue(sql.contains("INTERVAL #{businessDayShiftSeconds} SECOND"),
                "切日必须由 ReportTimeBuckets 的营业日平移量下推，不能写死偏移或按 UTC 自然日切");
        assertTrue(sql.contains("AS business_date"), "分组用的营业日必须有稳定别名");
        assertTrue(sql.contains("GROUP BY") && sql.contains("business_date"),
                "GROUP BY 必须用别名（重复表达式会因占位符不同被 ONLY_FULL_GROUP_BY 拒绝）");
    }

    // —— 辅助 ——

    /** 取 mapper 方法上的 {@code @Select} SQL 文本（{@code @Select} 在字符串数组的 values 里）。 */
    private static String selectSql(String methodName) throws Exception {
        Method method = ReportMapper.class.getMethod(methodName, Long.class, Long.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class, int.class);
        Select select = method.getAnnotation(Select.class);
        assertTrue(select != null, methodName + " 必须保留 @Select 注解");
        return String.join(" ", select.value());
    }

    private static void setTenant() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    }

    private static Map<String, Object> refund(String businessDate, String currencyCode, long count, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_id", STORE_ID);
        row.put("store_name", "A店");
        row.put("business_date", businessDate);
        row.put("currency_code", currencyCode);
        row.put("refund_count", count);
        row.put("refund_amount", new BigDecimal(amount));
        return row;
    }
}
