package io.openware.platform.admin.infra.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 资源利用率 SQL（{@code selectResourceUtilization}）的口径守卫 —— 把「按次开台」钉在 SQL 层。
 *
 * <p>这条报表出过一次真实线上缺陷（ACK 实测）：它从 {@code res_occupation} 取「占用窗口」，
 * 而占用是开台时写死的 {@code now → now + 24h}、结台不回缩 {@code end_at}，再按 (门店, 包厢, 营业日)
 * 求和，于是「开 4 次台 = 96 小时」。修复后的口径是：**一次开台一行，时长只来自会话**
 * （{@code ord_ktv_session.opened_at → closed_at − paused_seconds}）。
 *
 * <p>这里逐条钉住最容易回退的点：
 * <ol>
 *   <li>数据源必须是 {@code ord_ktv_session}，SQL 里**不得再出现 {@code res_occupation}**；</li>
 *   <li>时长必须由会话窗口算（扣暂停、未结台记 0、非负兜底），且**没有 GROUP BY / SUM 聚合**——
 *       一行就是一次开台；</li>
 *   <li>{@code turnover_count} 必须按「会话是否 CLOSED」判，不能再数 RELEASED 占用行；</li>
 *   <li>营业日按 {@code opened_at}（与取数区间同一列），偏移量仍由调用方传入（营业日守卫统一检查）。</li>
 * </ol>
 */
class ReportResourceUtilizationMapperContractTest {

    @Test
    @DisplayName("数据源是 KTV 会话，不再读 res_occupation 的占用窗口")
    void durationComesFromSessionNotOccupation() {
        String sql = sqlOf("selectResourceUtilization");

        assertTrue(sql.contains("FROM ord_ktv_session ks"), "时长只能来自 ord_ktv_session");
        assertFalse(sql.contains("res_occupation"),
                "不得再从 res_occupation 取数：占用窗口是固定 24h，结台不回缩 end_at，求和必然虚高");
        assertFalse(sql.contains("TIMESTAMPDIFF(SECOND, oc.start_at, oc.end_at)"),
                "旧口径「占用窗口求和」不得回归");
        assertFalse(sql.contains("oc.start_at") || sql.contains("oc.end_at"), "不得残留任何占用窗口列");
        assertTrue(sql.contains("JOIN ord_order o ON o.id = ks.order_id AND o.tenant_id = ks.tenant_id"),
                "门店取订单（会话表没有门店列），门店过滤与其它报表同一写法");
        assertTrue(sql.contains("JOIN res_resource r ON r.id = ks.room_resource_id AND r.tenant_id = ks.tenant_id"),
                "包厢名／房型过滤仍取资源表");
        assertTrue(sql.contains("r.resource_type = 'KTV_ROOM'"), "本报表只统计 KTV 包厢");
    }

    @Test
    @DisplayName("一次开台一行：没有 GROUP BY / SUM，时长按会话窗口算并扣暂停")
    void oneRowPerSessionWithoutAggregation() {
        String sql = sqlOf("selectResourceUtilization");

        assertFalse(sql.contains("GROUP BY"), "一行 = 一次开台（会话表主键），不得再按包厢/营业日聚合");
        assertFalse(sql.contains("SUM(") || sql.contains("COUNT("), "不得对占用窗口求和/计数");
        assertTrue(sql.contains("CASE WHEN ks.closed_at IS NULL THEN 0"),
                "未结台记 0：不拿「现在」当结束时间（否则每次查询数字都在变）");
        assertTrue(sql.contains("TIMESTAMPDIFF(SECOND, ks.opened_at, ks.closed_at)"),
                "时长 = 会话的 opened_at → closed_at");
        assertTrue(sql.contains("COALESCE(ks.paused_seconds, 0)"), "必须扣掉暂停时长");
        assertTrue(sql.contains("GREATEST("), "脏数据兜底：时长不得为负");
        assertTrue(sql.contains("AS duration_seconds"), "时长字段名与响应 durationSeconds 对齐");
        assertTrue(sql.contains("ks.id AS session_id"), "必须带出会话 ID（一次开台一行的身份）");
        assertTrue(sql.contains("ks.status AS session_status"), "必须带出会话状态（区分进行中/已完成）");
    }

    @Test
    @DisplayName("turnover_count = 该次开台是否已完成（CLOSED=1），不再数 RELEASED 占用")
    void turnoverCountMeansCompletion() {
        String sql = sqlOf("selectResourceUtilization");

        assertTrue(sql.contains("CASE WHEN ks.status = 'CLOSED' THEN 1 ELSE 0 END AS turnover_count"),
                "翻台次数口径 = 完成场次数（同行同包厢同营业日相加）");
        assertFalse(sql.contains("'RELEASED'"),
                "RELEASED 是「占用被释放」而不是「结台」，不得再拿它当翻台次数");
    }

    @Test
    @DisplayName("营业日与取数区间都按 opened_at（跨零点场次归前一营业日）")
    void businessDayAndWindowUseOpenedAt() {
        String sql = sqlOf("selectResourceUtilization");

        assertTrue(sql.contains(
                "DATE_FORMAT(DATE_ADD(ks.opened_at, INTERVAL #{businessDayShiftSeconds} SECOND), '%Y-%m-%d')"),
                "营业日按开台时间平移（偏移量不在 SQL 里写死）");
        assertTrue(sql.contains("ks.opened_at >= #{from} AND ks.opened_at < #{to}"),
                "取数区间与营业日同一列，避免「按 A 列过滤、按 B 列分桶」");
        assertTrue(sql.contains("(#{storeId} IS NULL OR o.store_id = #{storeId})"), "门店过滤可选");
        assertTrue(sql.contains("ks.status <> 'CANCELLED'"), "已取消的会话不计入资源消费");
        assertTrue(sql.contains("ks.opened_at IS NOT NULL"), "没有真正开台的会话（未开台/已取消预约）不计入");
    }

    @Test
    @DisplayName("参数名与 SQL 占位符一致，营业日平移量由调用方传入")
    void parametersMatchPlaceholders() {
        Set<String> params = Arrays.stream(methodOf("selectResourceUtilization").getParameters())
                .map(parameter -> parameter.getAnnotation(Param.class))
                .filter(java.util.Objects::nonNull)
                .map(Param::value)
                .collect(Collectors.toSet());

        assertTrue(params.containsAll(Set.of("tenantId", "storeId", "from", "to", "businessDayShiftSeconds")),
                "参数名必须与 SQL 占位符一致，实际: " + params);
        List<java.lang.reflect.Parameter> shift = Arrays.stream(methodOf("selectResourceUtilization").getParameters())
                .filter(parameter -> {
                    Param param = parameter.getAnnotation(Param.class);
                    return param != null && "businessDayShiftSeconds".equals(param.value());
                })
                .toList();
        assertTrue(shift.size() == 1 && shift.get(0).getType() == int.class,
                "businessDayShiftSeconds 必须是唯一的 int 参数");
    }

    // —— 辅助 ——

    private static Method methodOf(String methodName) {
        for (Method method : ReportMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("ReportMapper 缺少方法: " + methodName);
    }

    private static String sqlOf(String methodName) {
        Select select = methodOf(methodName).getAnnotation(Select.class);
        assertTrue(select != null, methodName + " 必须保留 @Select");
        return String.join(" ", select.value());
    }
}
