package com.gvchat.platform.admin.infra.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.infrastructure.time.ReportTimeBuckets;
import com.gvchat.infrastructure.time.StoreTimeService;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 报表 SQL 的**营业日契约**守卫：切日只有一处口径（{@link ReportTimeBuckets}），SQL 不得自造。
 *
 * <p>为什么需要它：报表把「营业日」下推成了一个 SQL 表达式，如果将来有人
 * (a) 加一条新报表却忘了带营业日平移、(b) 把偏移写死成 {@code INTERVAL 8 HOUR}、
 * (c) 在 GROUP BY 里重复表达式（占位符不同 → {@code ONLY_FULL_GROUP_BY} 直接 1055，已实测），
 * 只要口径与 {@link ReportTimeBuckets} 不一致，本测试立刻红。
 */
class ReportMapperBusinessDayContractTest {

    /** 营业日表达式里必须出现的占位符：偏移量由调用方传入 SDK 常量，不在 SQL 里写死。 */
    private static final String SHIFT_PLACEHOLDER = "INTERVAL #{businessDayShiftSeconds} SECOND";

    /** 明细行额外保留的「存储值 ISO 时间」（与订单页口径一致，故意不平移）。 */
    private static final String ISO_PATTERN = "%Y-%m-%dT%H:%i:%s";

    @Test
    @DisplayName("凡按营业日分组的查询都必须用 SDK 传入的平移量（不写死偏移）")
    void everyBusinessDayQueryUsesSharedShift() {
        int checked = 0;
        for (Method method : ReportMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            String sql = String.join(" ", select.value());
            if (!sql.contains("AS business_date")) {
                continue;
            }
            checked++;
            assertTrue(sql.contains(SHIFT_PLACEHOLDER),
                    method.getName() + " 的营业日必须由 " + SHIFT_PLACEHOLDER + " 计算");
            assertTrue(hasShiftParameter(method),
                    method.getName() + " 必须声明 @Param(\"businessDayShiftSeconds\") int 参数");
            assertFalse(sql.contains("INTERVAL 8 HOUR") || sql.contains("INTERVAL 4 HOUR")
                            || sql.contains("INTERVAL 8 HOUR)") || sql.contains("CONVERT_TZ"),
                    method.getName() + " 不得写死营业时区偏移（口径只在 ReportTimeBuckets）");
            // 聚合查询的 GROUP BY 必须用别名：重复整个表达式会因占位符不同被 ONLY_FULL_GROUP_BY 判为不等价
            // （明细列表不是聚合查询，逐行返回，本来就没有 GROUP BY）
            if (sql.contains("SUM(") || sql.contains("COUNT(")) {
                int groupBy = sql.indexOf("GROUP BY");
                assertTrue(groupBy > 0, method.getName() + " 是聚合查询，必须有 GROUP BY");
                assertTrue(sql.substring(groupBy).contains("business_date"),
                        method.getName() + " 的 GROUP BY 必须用 business_date 别名");
            }
        }
        assertTrue(checked >= 11, "至少 11 条报表查询按营业日分组（当前 " + checked + "）");
    }

    @Test
    @DisplayName("每个 DATE_FORMAT 要么走营业日平移，要么是刻意保留的存储值 ISO 时间")
    void everyDateFormatIsAccountedFor() {
        for (Method method : ReportMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            String sql = String.join(" ", select.value());
            int formats = occurrences(sql, "DATE_FORMAT(");
            int shifts = occurrences(sql, "DATE_ADD(");
            int iso = occurrences(sql, ISO_PATTERN);
            assertEquals(formats, shifts + iso,
                    method.getName() + " 出现未平移的日期切分（UTC 自然日会算错营业日）");
            assertEquals(shifts, occurrences(sql, SHIFT_PLACEHOLDER),
                    method.getName() + " 的 DATE_ADD 必须全部使用共享平移量占位符");
        }
    }

    @Test
    @DisplayName("销售明细的列表与总数用同一套过滤条件（否则分页 total 与列表对不上）")
    void salesDetailsCountAndPageShareFilters() {
        String count = sqlOf("countSalesDetails");
        String page = sqlOf("selectSalesDetails");

        for (String condition : new String[] {
            "o.tenant_id = #{tenantId}",
            "o.created_at >= #{from} AND o.created_at < #{to}",
            "(#{storeId} IS NULL OR o.store_id = #{storeId})",
            "(#{status} IS NULL OR o.status = #{status})",
            "(#{status} IS NOT NULL OR o.status NOT IN ('CANCELLED','VOIDED'))",
        }) {
            assertTrue(count.contains(condition), "总数查询缺少条件: " + condition);
            assertTrue(page.contains(condition), "明细查询缺少条件: " + condition);
        }
        assertTrue(page.contains("ORDER BY o.created_at DESC, o.id DESC"), "明细必须稳定倒序");
        assertTrue(page.contains("LIMIT #{limit} OFFSET #{offset}"), "明细必须服务端分页");
    }

    @Test
    @DisplayName("支付构成按订单 id 批量取（分页后只查当前页，不按区间全量）")
    void paymentCompositionIsScopedToPage() {
        String sql = sqlOf("selectOrderPaymentComposition");
        assertTrue(sql.contains("<foreach collection=\"orderIds\""), "必须按当前页订单 id 批量查询");
        assertTrue(sql.contains("t.status = 'SUCCEEDED'"), "只统计到账流水");
        assertTrue(sql.contains("COALESCE(t.provider, pi.provider)"), "历史行 provider 为空时回落支付意图");
    }

    @Test
    @DisplayName("平移量与平台默认门店口径（StoreTimeService）一致")
    void shiftMatchesStoreTimeServiceDefaults() {
        int zoneSeconds = ZoneId.of(StoreTimeService.DEFAULT_TIMEZONE).getRules().getOffset(Instant.now())
                .getTotalSeconds();
        int expected = zoneSeconds - StoreTimeService.DEFAULT_BUSINESS_DAY_CUTOFF.toSecondOfDay();
        assertEquals(expected, ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS,
                "SQL 用的平移量必须等于「门店时区偏移 − 营业日切点」");
    }

    // —— 辅助 ——

    private static String sqlOf(String methodName) {
        for (Method method : ReportMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertTrue(select != null, methodName + " 必须保留 @Select");
                return String.join(" ", select.value());
            }
        }
        throw new AssertionError("ReportMapper 缺少方法: " + methodName);
    }

    private static boolean hasShiftParameter(Method method) {
        return Arrays.stream(method.getParameters()).anyMatch(ReportMapperBusinessDayContractTest::isShiftParameter);
    }

    private static boolean isShiftParameter(Parameter parameter) {
        Param param = parameter.getAnnotation(Param.class);
        return param != null && "businessDayShiftSeconds".equals(param.value()) && parameter.getType() == int.class;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
