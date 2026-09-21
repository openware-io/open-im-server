package com.gvchat.platform.admin.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.ReportGranularity;
import com.gvchat.infrastructure.time.ReportTimeBuckets;
import com.gvchat.infrastructure.time.ReportTimeBuckets.TimeBucket;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 报表只读聚合（SAAS_PLATFORM_02 §9 ADM-15 / SAAS_PLATFORM_07 E6 / D-13）。
 * <p>员工业绩 / 经营报表 / 支付报表 / 资源利用率 / 库存成本与毛利 / 销售报表，均只读聚合现有业务表，
 * 不建领域表、不写数据；租户边界取自 X-Tenant-Context（TenantContextHolder），所有响应携带
 * {@code dataAsOf} 标注数据时点。</p>
 *
 * <h2>时间口径（全仓统一，不得各报表自造）</h2>
 * <ul>
 *   <li><b>取数区间</b>：{@code from}/{@code to} 经 {@link TimeRangeParams#parse} 解析——
 *       <b>闭区间</b>、{@code yyyy-MM-dd} 收口到整天、非法或 {@code from > to} → 400
 *       {@link TimeRangeParams#CODE_TIME_RANGE_INVALID}。本类**不**自己解析时间字符串。</li>
 *   <li><b>营业日</b>：区间端点被解释成**营业日**（门店本地日 + 营业日切点），下推到 SQL 的
 *       窗口是 {@code [from 切点, (to+1) 切点)}（存储值形态），与 {@code ReportTimeBuckets} 的桶边界
 *       严格对齐。口径与已知边界见 {@link ReportTimeBuckets} 的类注释。</li>
 *   <li><b>粒度</b>：{@code granularity=DAY|WEEK|MONTH|YEAR}（缺省 DAY），经
 *       {@link ReportGranularity#parse} 解析，非法值 → 400 {@code GRANULARITY_INVALID}。
 *       日→周/月/年的分桶只有 {@link ReportTimeBuckets#bucket} 一处实现。</li>
 * </ul>
 *
 * <h2>币种口径（规范 §3.6）</h2>
 * <p>每行金额都带同级 {@code currencyCode}（该记录的币种快照，缺省 USD）。同一门店同一时间桶
 * 出现多行即多币种，**禁止**把多行金额相加；混币种报表在信封上以 {@code currencyCode=null} +
 * {@code mixedCurrency=true} 显式声明（前端据此提示「多币种，禁止合计」）。
 */
@RestController
@RequestMapping("/admin/reports")
public class ReportController {

    /** 金额定点精度：与 {@code decimal(20,6)} 一致（最小货币单位）。 */
    private static final int COST_SCALE = 6;
    /** 毛利率小数位（比例值，非百分比；前端乘 100 展示）。 */
    private static final int MARGIN_SCALE = 4;
    /** 成本口径标识：期末移动加权平均成本 × 期间净售出数量（近似，见接口注释）。 */
    private static final String COST_BASIS_PERIOD_END_MOVING_AVERAGE = "PERIOD_END_MOVING_AVERAGE";

    /** 利用率口径标识：该包厢该营业日的**会话时长合计** ÷ 营业日可用时长（24h），分子只来自会话。 */
    private static final String UTILIZATION_BASIS_ROOM_DAY = "ROOM_DAY_SESSION_SUM_OVER_24H";

    /**
     * 营业日可用时长（秒）：24 小时。
     *
     * <p>当前数据模型没有「门店营业时段」配置（{@code tnt_store} 只有营业日切点，没有开闭店时间），
     * 因此营业日的房间可用时长只能按 24h 计——这是**显式近似**，写在响应信封的 {@code utilizationBasis} 上，
     * 避免使用方把利用率当成精确值。
     */
    private static final long BUSINESS_DAY_AVAILABLE_SECONDS = 24L * 60 * 60;

    /** 利用率小数位（比例值，非百分比；前端乘 100 展示，与 {@code formatPercent} 口径一致）。 */
    private static final int RATE_SCALE = 4;

    /** 未传 {@code from} 时的默认起点：今天往前 30 天（含今天 31 天），与历史行为一致。 */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** 明细分页默认/上限（沿用后台列表约定：Page 信封 records/total/current/size）。 */
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 明细可按状态过滤的取值（来源：{@code V1__ord_order_baseline.sql:8} 的 {@code ord_order.status} 注释）。
     * 非法状态直接 400，不静默返回空表——否则用户以为「这个状态没有单据」。
     */
    private static final Set<String> ORDER_STATUSES = Set.of(
            "DRAFT", "WAITING_PAYMENT", "WAITING_ARRIVAL", "SERVING", "WAITING_SETTLEMENT",
            "COMPLETED", "CANCELLED", "VOIDED", "PARTIAL_REFUNDED", "REFUNDED");

    /** 收款方式归类：线上渠道（其余非现金/储值/积分的取值一律归「其它」，不静默丢弃）。 */
    private static final Set<String> ONLINE_PROVIDERS = Set.of("ALIPAY", "WECHAT", "STRIPE");

    /** 币种排序：快照缺失（理论不该出现）排最后，绝不因为 null 抛 NPE（历史 bug，见 payments 注释）。 */
    private static final Comparator<String> CURRENCY_ORDER = Comparator.nullsLast(Comparator.naturalOrder());

    /** 营业日标签用的门店/租户时区文本（响应里显式声明，前端提示「营业日」口径）。 */
    private static final String BUSINESS_ZONE = ReportTimeBuckets.REPORT_TIMEZONE;

    private final ReportMapper reportMapper;

    public ReportController(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /** 员工业绩（D-13）：按员工（收银/服务人员）× 统计桶聚合开单数/服务时长/收款额/加项数。 */
    @GetMapping("/employee-performance")
    public EmployeePerformanceReport employeePerformance(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        Map<String, EmployeeAgg> byKey = new LinkedHashMap<>();
        for (Map<String, Object> m : reportMapper.selectCashierOpenOrders(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds())) {
            EmployeeAgg agg = byKey.computeIfAbsent(employeeKey("CASHIER", m, scope), k -> cashier(m, scope));
            agg.openOrderCount += longVal(m, "open_order_count");
        }
        for (Map<String, Object> m : reportMapper.selectCashierCollections(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds())) {
            EmployeeAgg agg = byKey.computeIfAbsent(employeeKey("CASHIER", m, scope), k -> cashier(m, scope));
            agg.collectedAmount = agg.collectedAmount.add(decimalVal(m, "collected_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectCashierAddOns(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds())) {
            EmployeeAgg agg = byKey.computeIfAbsent(employeeKey("CASHIER", m, scope), k -> cashier(m, scope));
            agg.addOnCount += longVal(m, "add_on_count");
        }
        for (Map<String, Object> m : reportMapper.selectServerPerformance(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds())) {
            EmployeeAgg agg = byKey.computeIfAbsent(employeeKey("SERVER", m, scope), k -> server(m, scope));
            agg.serviceSeconds += longVal(m, "service_seconds");
            agg.addOnCount += longVal(m, "add_on_count");
        }
        List<EmployeePerformanceReport.Row> rows = new ArrayList<>();
        for (EmployeeAgg agg : byKey.values()) {
            rows.add(new EmployeePerformanceReport.Row(agg.type, agg.employeeId, agg.employeeName, agg.storeId,
                    agg.bucket, agg.openOrderCount, agg.serviceSeconds, agg.collectedAmount, agg.currencyCode,
                    agg.addOnCount));
        }
        rows.sort(Comparator.comparing((EmployeePerformanceReport.Row r) -> r.bucket().start())
                .thenComparing(EmployeePerformanceReport.Row::employeeType)
                .thenComparing(r -> r.employeeId() == null ? 0L : r.employeeId())
                .thenComparing(EmployeePerformanceReport.Row::currencyCode, CURRENCY_ORDER));
        return new EmployeePerformanceReport(Instant.now(), scope.fromDay(), scope.toDay(), scope.granularityCode(),
                rows);
    }

    /** 经营报表：按门店/统计桶聚合订单数/应收/实收/退款/客单。 */
    @GetMapping("/operations")
    public OperationsReport operations(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        Map<String, OperationsAgg> byKey = new LinkedHashMap<>();
        for (Map<String, Object> m : reportMapper.selectOperations(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            OperationsAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> operations(m, scope));
            agg.orderCount += longVal(m, "order_count");
            agg.receivable = agg.receivable.add(decimalVal(m, "receivable_amount"));
            agg.paid = agg.paid.add(decimalVal(m, "paid_amount"));
            agg.discount = agg.discount.add(decimalVal(m, "discount_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectRefunds(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            // 退款按 (门店, 桶, 币种) 独立成行：币种不同绝不并入同一行（跨币种禁止静默求和）。
            // 状态口径由 SQL 侧锁定为终态 REFUNDED（见 ReportMapper#selectRefunds），待审批/已驳回不计入。
            OperationsAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> operations(m, scope));
            agg.refundCount += longVal(m, "refund_count");
            agg.refund = agg.refund.add(decimalVal(m, "refund_amount"));
        }
        List<OperationsReport.Row> rows = new ArrayList<>();
        for (OperationsAgg agg : byKey.values()) {
            BigDecimal avg = agg.orderCount == 0 ? BigDecimal.ZERO
                    : agg.receivable.divide(BigDecimal.valueOf(agg.orderCount), 2, RoundingMode.HALF_UP);
            rows.add(new OperationsReport.Row(agg.storeId, agg.storeName, agg.bucket, agg.currencyCode,
                    agg.orderCount, agg.receivable, agg.paid, agg.refund, agg.discount, avg));
        }
        sortByBucket(rows, OperationsReport.Row::bucket, OperationsReport.Row::storeId,
                OperationsReport.Row::currencyCode);
        return new OperationsReport(Instant.now(), scope.fromDay(), scope.toDay(), scope.granularityCode(), rows);
    }

    /**
     * 支付报表：按门店/统计桶聚合收款/退款（交易流水口径，区别于订单金额快照）。
     *
     * <p><b>历史缺陷（已修）</b>：聚合行由 {@code computeIfAbsent(key, k -> new PaymentsAgg())} 创建，
     * 而 {@code PaymentsAgg.currencyCode} 只在退款分支的工厂方法里赋值 —— 结果是
     * (1) 收款行 {@code currencyCode} 恒为 null，前端只能回落到「当前币种」渲染，历史快照丢失；
     * (2) 排序末位 {@code thenComparing(Row::currencyCode)} 遇到两行同 (门店, 桶) 不同币种时
     * （正是「按币种分行」要处理的场景）抛 NPE，整个接口 500。
     * 现在聚合行一律由 {@link #payments(Map, Scope)} 工厂创建（币种/门店/桶同时落位），
     * 且排序一律用 {@link #CURRENCY_ORDER}（null 安全）。回归用例见
     * {@code ReportPaymentsReportContractTest}。
     */
    @GetMapping("/payments")
    public PaymentsReport payments(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        Map<String, PaymentsAgg> byKey = new LinkedHashMap<>();
        for (Map<String, Object> m : reportMapper.selectCollections(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            PaymentsAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> payments(m, scope));
            agg.collectionCount += longVal(m, "collection_count");
            agg.collected = agg.collected.add(decimalVal(m, "collected_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectRefunds(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            // 同经营报表：退款按 (门店, 桶, 币种) 独立成行，跨币种不并入；口径同为终态 REFUNDED。
            PaymentsAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> payments(m, scope));
            agg.refundCount += longVal(m, "refund_count");
            agg.refund = agg.refund.add(decimalVal(m, "refund_amount"));
        }
        List<PaymentsReport.Row> rows = new ArrayList<>();
        for (PaymentsAgg agg : byKey.values()) {
            rows.add(new PaymentsReport.Row(agg.storeId, agg.storeName, agg.bucket, agg.currencyCode,
                    agg.collectionCount, agg.collected, agg.refundCount, agg.refund));
        }
        sortByBucket(rows, PaymentsReport.Row::bucket, PaymentsReport.Row::storeId,
                PaymentsReport.Row::currencyCode);
        return new PaymentsReport(Instant.now(), scope.fromDay(), scope.toDay(), scope.granularityCode(), rows);
    }

    /**
     * 资源利用率：**按「每次开台」一行**（一次 KTV 消费 = 一行），时长取该次会话自己的
     * {@code opened_at → closed_at − paused_seconds}，不再按 {@code res_occupation} 的 24 小时占用窗口求和。
     *
     * <p><b>历史缺陷（已修，ACK 环境实测）</b>：占用窗口是开台时写死的 {@code now → now + 24h}，
     * 结台不回缩 {@code end_at}；旧实现把同一包厢同一天的占用窗口相加，开 4 次台就显示 96h。
     * 现在只按**会话**出数，多次开台 = 多行，各算各的（SQL 侧口径见
     * {@link com.gvchat.platform.admin.infra.persistence.mapper.ReportMapper#selectResourceUtilization}）。
     *
     * <p><b>字段口径（与 {@link ResourceUtilizationReport} 的注释、测试一一对应）</b>：
     * <ul>
     *   <li>{@code durationSeconds}：本次开台时长（秒）；未结台记 0，不拿「现在」当结束时间；</li>
     *   <li>{@code turnoverCount}：本次开台**是否已完成**（CLOSED=1，其它=0）。同一包厢同一营业日各行相加
     *       = 当日完成场次数，即翻台次数——不再用「RELEASED 占用行数」；</li>
     *   <li>{@code turnoverRate}：该包厢**该营业日**的利用率 =（当日会话时长合计）÷ 86400
     *       （营业日按 24 小时可用计）。分子只来自会话时长，与每行 {@code durationSeconds} 同源，
     *       绝不回落到占用窗口；同一包厢同营业日的每一行给同一个值（行内的 {@code durationSeconds}
     *       是「这一次」的时长，比率是「这一天」的口径，两者不混用）。</li>
     * </ul>
     */
    @GetMapping("/resources")
    public ResourceUtilizationReport resources(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        List<Map<String, Object>> sessions = reportMapper.selectResourceUtilization(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds());
        // 利用率的分母取「包厢 + 营业日」的会话时长合计（不是占用窗口）：先汇总，再给该包厢当日的每一行同一个值。
        Map<String, Long> roomDaySeconds = new LinkedHashMap<>();
        for (Map<String, Object> m : sessions) {
            roomDaySeconds.merge(roomDayKey(m), longVal(m, "duration_seconds"), Long::sum);
        }
        List<ResourceUtilizationReport.Row> rows = new ArrayList<>();
        for (Map<String, Object> m : sessions) {
            LocalDate businessDate = dateVal(m, "business_date");
            rows.add(new ResourceUtilizationReport.Row(longOrNull(m, "store_id"), stringVal(m, "store_name"),
                    longOrNull(m, "resource_id"), stringVal(m, "resource_name"), scope.bucketOf(businessDate),
                    businessDate, longOrNull(m, "session_id"), stringVal(m, "opened_at"), stringVal(m, "closed_at"),
                    longVal(m, "duration_seconds"), stringVal(m, "session_status"), longVal(m, "turnover_count"),
                    utilizationRate(roomDaySeconds.getOrDefault(roomDayKey(m), 0L))));
        }
        rows.sort(Comparator.comparing((ResourceUtilizationReport.Row r) -> r.bucket().start())
                .thenComparing(r -> r.storeId() == null ? 0L : r.storeId())
                .thenComparing(r -> r.resourceId() == null ? 0L : r.resourceId())
                .thenComparing(r -> r.sessionId() == null ? 0L : r.sessionId()));
        return new ResourceUtilizationReport(Instant.now(), scope.fromDay(), scope.toDay(), scope.granularityCode(),
                UTILIZATION_BASIS_ROOM_DAY, rows);
    }

    /**
     * 库存成本与毛利（按币种分组）。
     *
     * <p><b>口径</b>：
     * <ul>
     *   <li>期间收入 = 期间订单明细 {@code ord_order_item.total_amount} 之和，按**明细币种快照**
     *       {@code ord_order_item.currency_code} 归集（订单已取消/作废、明细非 ACTIVE 的不计）；</li>
     *   <li>期间成本 = 期间**净售出数量** × 该物料的**移动加权平均成本**
     *       （{@code ord_inventory_stock.avg_cost}）。净售出数量 = CONSUME 数量 − REVERSE 回补数量，
     *       回补是「销售未发生」的冲回；盘亏 ADJUST_OUT 属于损耗，不计入毛利成本；</li>
     *   <li>毛利 = 收入 − 成本；毛利率 = 毛利 / 收入（收入为 0 时返回 null，不做 0 除、也不假装是 0%）。</li>
     * </ul>
     *
     * <p><b>口径限制（必须向前端/使用方声明）</b>：成本用的是**期末**（查询时点）平均成本，而不是
     * 「售出当时」的成本流水 —— 因此期初/期中进过货的物料，本期成本会带有期末单价的成分；
     * 这是 {@code costBasis=PERIOD_END_MOVING_AVERAGE} 的明确近似，不做追溯重算。
     * 平均成本为 0（历史未建账、或从未维护采购价）的那部分数量不计入成本，
     * 单独以 {@code uncostedQuantity} 暴露，使用方据此判断成本覆盖度，避免把「无成本」误读成「零成本」。
     *
     * <p><b>粒度说明</b>：本报表按营业日聚合，因此日/周/月/年四档都能给；但成本单价取自**查询时点**的
     * 平均成本，所以周/月/年桶带来的只是「数量按桶累计」，成本精度不会因为桶更大而变准
     * （{@code costBasis} 的近似对每一档同样成立）。
     *
     * <p><b>币种</b>：收入按明细币种、成本按库存行平均成本币种分组，同一 (门店, 桶, 币种) 才会并到一行；
     * 两种金额币种不同时会各自成行（信封 {@code mixedCurrency=true}、{@code currencyCode=null}），
     * 绝不跨币种相减或求和。
     */
    @GetMapping("/inventory-gross-profit")
    public InventoryGrossProfitReport inventoryGrossProfit(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        Map<String, GrossProfitAgg> byKey = new LinkedHashMap<>();
        for (Map<String, Object> m : reportMapper.selectItemRevenue(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            GrossProfitAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> grossProfit(m, scope));
            agg.revenueAmount = agg.revenueAmount.add(decimalVal(m, "revenue_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectInventoryConsumptionCost(tenantId, storeId, scope.from(),
                scope.to(), scope.shiftSeconds())) {
            GrossProfitAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> grossProfit(m, scope));
            // 数量与平均成本都是定点值：金额 = 数量 × 平均成本，保留 6 位（与 decimal(20,6) 一致）。
            BigDecimal quantity = decimalVal(m, "net_quantity");
            BigDecimal avgCost = decimalVal(m, "avg_cost");
            BigDecimal amount = quantity.multiply(avgCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
            agg.soldQuantity = agg.soldQuantity.add(quantity);
            agg.costAmount = agg.costAmount.add(amount);
            if (avgCost.signum() <= 0) {
                agg.uncostedQuantity = agg.uncostedQuantity.add(quantity);
            }
        }
        List<InventoryGrossProfitReport.Row> rows = new ArrayList<>();
        for (GrossProfitAgg agg : byKey.values()) {
            BigDecimal revenue = agg.revenueAmount.setScale(COST_SCALE, RoundingMode.HALF_UP);
            BigDecimal cost = agg.costAmount.setScale(COST_SCALE, RoundingMode.HALF_UP);
            BigDecimal grossProfit = revenue.subtract(cost);
            BigDecimal marginRate = revenue.signum() == 0 ? null
                    : grossProfit.divide(revenue, MARGIN_SCALE, RoundingMode.HALF_UP);
            rows.add(new InventoryGrossProfitReport.Row(agg.storeId, agg.storeName, agg.bucket, agg.currencyCode,
                    revenue, cost, grossProfit, marginRate,
                    agg.soldQuantity.setScale(COST_SCALE, RoundingMode.HALF_UP),
                    agg.uncostedQuantity.setScale(COST_SCALE, RoundingMode.HALF_UP)));
        }
        sortByBucket(rows, InventoryGrossProfitReport.Row::bucket, InventoryGrossProfitReport.Row::storeId,
                InventoryGrossProfitReport.Row::currencyCode);
        return new InventoryGrossProfitReport(Instant.now(), scope.fromDay(), scope.toDay(),
                scope.granularityCode(),
                singleCurrency(rows.stream().map(InventoryGrossProfitReport.Row::currencyCode).toList()),
                rows.stream().map(InventoryGrossProfitReport.Row::currencyCode).distinct().count() > 1,
                COST_BASIS_PERIOD_END_MOVING_AVERAGE, rows);
    }

    /**
     * 销售报表：**统计 + 明细**同区间同粒度。
     *
     * <p><b>统计</b>（{@code buckets}）：按 (门店, 统计桶, 币种) 一行，给出
     * 销售额（有效单据 {@code total_amount}）、订单数、客单价、折扣/优惠、退款金额（终态 REFUNDED）、
     * 作废金额（{@code status='VOIDED'}）、收款金额与**按支付方式的构成**
     * （现金 / 线上 / 储值币 / 积分 / 其它）。
     *
     * <p><b>明细</b>（{@code details}）：区间内的销售单据，按创建时间倒序**分页**
     * （Page 信封 {@code records/total/current/size}），带订单号/时间/营业日/门店/包厢/金额/状态/支付构成。
     * 明细默认口径与统计侧完全一致（{@code status NOT IN ('CANCELLED','VOIDED')}），
     * 因此 {@code details.total} 等于统计行订单数之和（可用 {@code status} 参数单独复核作废/取消单）。
     *
     * <p><b>为什么一个端点同时给统计与明细</b>：两者必须在**同一份 from/to/granularity** 下口径一致
     * （同一窗口、同一营业日边界、同一状态口径）。拆成两个端点后，「统计按周、明细按日」这类
     * 口径漂移只能靠调用方自律；同端点则不存在这个可能。
     */
    @GetMapping("/sales")
    public SalesReport sales(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, granularity);
        String detailStatus = normalizeStatus(status);

        Map<String, SalesAgg> byKey = new LinkedHashMap<>();
        for (Map<String, Object> m : reportMapper.selectSalesOrders(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            SalesAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> sales(m, scope));
            agg.orderCount += longVal(m, "order_count");
            agg.salesAmount = agg.salesAmount.add(decimalVal(m, "sales_amount"));
            agg.discountAmount = agg.discountAmount.add(decimalVal(m, "discount_amount"));
            agg.paidAmount = agg.paidAmount.add(decimalVal(m, "paid_amount"));
            agg.voidedCount += longVal(m, "voided_count");
            agg.voidedAmount = agg.voidedAmount.add(decimalVal(m, "voided_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectRefunds(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            SalesAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> sales(m, scope));
            agg.refundCount += longVal(m, "refund_count");
            agg.refundAmount = agg.refundAmount.add(decimalVal(m, "refund_amount"));
        }
        for (Map<String, Object> m : reportMapper.selectSalesPayments(tenantId, storeId, scope.from(), scope.to(),
                scope.shiftSeconds())) {
            SalesAgg agg = byKey.computeIfAbsent(bucketKey(m, scope), k -> sales(m, scope));
            BigDecimal amount = decimalVal(m, "payment_amount");
            agg.paymentCount += longVal(m, "payment_count");
            agg.collectedAmount = agg.collectedAmount.add(amount);
            switch (PayChannel.of(stringVal(m, "provider"))) {
                case CASH -> agg.cashAmount = agg.cashAmount.add(amount);
                case ONLINE -> agg.onlineAmount = agg.onlineAmount.add(amount);
                case WALLET -> agg.walletAmount = agg.walletAmount.add(amount);
                case POINTS -> agg.pointsAmount = agg.pointsAmount.add(amount);
                default -> agg.otherAmount = agg.otherAmount.add(amount);
            }
        }

        List<SalesReport.BucketRow> buckets = new ArrayList<>();
        for (SalesAgg agg : byKey.values()) {
            BigDecimal averageTicket = agg.orderCount == 0 ? BigDecimal.ZERO
                    : agg.salesAmount.divide(BigDecimal.valueOf(agg.orderCount), 2, RoundingMode.HALF_UP);
            buckets.add(new SalesReport.BucketRow(agg.storeId, agg.storeName, agg.bucket, agg.currencyCode,
                    agg.orderCount, agg.salesAmount, agg.discountAmount, agg.paidAmount, averageTicket,
                    agg.refundCount, agg.refundAmount, agg.voidedCount, agg.voidedAmount,
                    agg.paymentCount, agg.collectedAmount, agg.cashAmount, agg.onlineAmount, agg.walletAmount,
                    agg.pointsAmount, agg.otherAmount));
        }
        sortByBucket(buckets, SalesReport.BucketRow::bucket, SalesReport.BucketRow::storeId,
                SalesReport.BucketRow::currencyCode);

        int size = clampPageSize(pageSize);
        long current = Math.max(DEFAULT_PAGE, page == null ? DEFAULT_PAGE : page);
        long total = reportMapper.countSalesDetails(tenantId, storeId, scope.from(), scope.to(), detailStatus);
        List<Map<String, Object>> detailRows = total == 0 ? List.of()
                : reportMapper.selectSalesDetails(tenantId, storeId, scope.from(), scope.to(), detailStatus, size,
                        (current - 1) * size, scope.shiftSeconds());
        Map<Long, SalesReport.PaymentComposition> compositions = loadPaymentCompositions(tenantId, detailRows);
        List<SalesReport.DetailRow> records = new ArrayList<>();
        for (Map<String, Object> m : detailRows) {
            Long orderId = longOrNull(m, "order_id");
            records.add(new SalesReport.DetailRow(orderId, stringVal(m, "order_no"), stringVal(m, "created_at"),
                    dateVal(m, "business_date"), longOrNull(m, "store_id"), stringVal(m, "store_name"),
                    stringVal(m, "room_name"), stringVal(m, "status"), currencyVal(m),
                    decimalVal(m, "subtotal_amount"), decimalVal(m, "discount_amount"),
                    decimalVal(m, "total_amount"), decimalVal(m, "paid_amount"),
                    decimalVal(m, "refundable_amount"),
                    compositions.getOrDefault(orderId, SalesReport.PaymentComposition.empty())));
        }

        List<String> currencies = new ArrayList<>(buckets.stream().map(SalesReport.BucketRow::currencyCode).toList());
        currencies.addAll(records.stream().map(SalesReport.DetailRow::currencyCode).toList());
        return new SalesReport(Instant.now(), scope.fromDay(), scope.toDay(), scope.granularityCode(), BUSINESS_ZONE,
                detailStatus, singleCurrency(currencies),
                currencies.stream().distinct().count() > 1,
                buckets, new SalesReport.SalesDetails(records, total, current, size));
    }

    /**
     * 商品/服务销售排行：`GET /admin/reports/sales-items?storeId=&from=&to=&itemType=&topN=`
     *
     * <p>回答「哪些商品/服务卖得好、各占多少」。口径与销售报表同源：同一套 {@code storeId/from/to}、
     * 排除已取消/已作废订单、只算已生效明细（待确认/被拒的加项不计入），详见
     * {@link ReportMapper#selectSalesItems}。
     *
     * <p>{@code itemType} 取 {@code PRODUCT}（商品）/ {@code SERVICE}（服务）/ {@code ROOM_FEE}（包厢费）/
     * {@code ADD_ON}（加项：目录项类型取不到时的兜底），空 = 全部；{@code topN} 默认 20、上限 200。
     *
     * <p>{@code rows} 按销售额降序；{@code share} 是**同币种内**该行占当前筛选结果合计的比例
     * （混币种时禁止跨币种求和，占比也在同币种内算）；{@code storeCount} 是售卖门店数
     * （未按门店筛选时用来看铺货面）。
     */
    @GetMapping("/sales-items")
    public SalesItemsReport salesItems(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) Integer topN) {
        Long tenantId = requireTenant();
        Scope scope = Scope.of(from, to, null);
        String category = normalizeItemCategory(itemType);
        int limit = clampTopN(topN);
        List<Map<String, Object>> itemRows = reportMapper.selectSalesItems(tenantId, storeId, scope.from(),
                scope.to(), category, limit);

        Map<String, BigDecimal> totalsByCurrency = new LinkedHashMap<>();
        for (Map<String, Object> m : itemRows) {
            String key = currencyKey(m);
            totalsByCurrency.merge(key, decimalVal(m, "sales_amount"), BigDecimal::add);
        }
        List<SalesItemsReport.Row> rows = new ArrayList<>();
        for (Map<String, Object> m : itemRows) {
            String key = currencyKey(m);
            BigDecimal salesAmount = decimalVal(m, "sales_amount");
            BigDecimal total = totalsByCurrency.getOrDefault(key, BigDecimal.ZERO);
            rows.add(new SalesItemsReport.Row(
                    stringVal(m, "item_category"), stringVal(m, "item_name"), currencyVal(m),
                    longVal(m, "store_count"), decimalVal(m, "quantity"), salesAmount,
                    decimalVal(m, "discount_amount"), longVal(m, "order_count"),
                    total.signum() == 0 ? null : salesAmount.divide(total, 4, RoundingMode.HALF_UP)));
        }
        List<SalesItemsReport.CurrencyTotal> totals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : totalsByCurrency.entrySet()) {
            totals.add(new SalesItemsReport.CurrencyTotal(entry.getKey().isEmpty() ? null : entry.getKey(),
                    entry.getValue()));
        }
        List<String> currencies = new ArrayList<>(rows.stream().map(SalesItemsReport.Row::currencyCode).toList());
        return new SalesItemsReport(Instant.now(), scope.fromDay(), scope.toDay(), category, limit,
                singleCurrency(currencies),
                currencies.stream().filter(java.util.Objects::nonNull).distinct().count() > 1, totals, rows);
    }

    /** 商品/服务销售排行（同一 from/to 口径下的区间汇总，不分营业日档）。 */
    public record SalesItemsReport(Instant dataAsOf, LocalDate from, LocalDate to, String itemType, Integer topN,
                                   String currencyCode, boolean mixedCurrency, List<CurrencyTotal> totals,
                                   List<Row> rows) {
        /** 同币种合计（占比分母；混币种时逐币种给出，前端不得跨币种求和）。 */
        public record CurrencyTotal(String currencyCode, BigDecimal salesAmount) {}

        /** 一行 = 一个商品/服务（同币种、同品类）的区间汇总。 */
        public record Row(String itemType, String itemName, String currencyCode, long storeCount,
                          BigDecimal quantity, BigDecimal salesAmount, BigDecimal discountAmount,
                          long orderCount, BigDecimal share) {}
    }

    /** 商品/服务排行支持的品类；未登记的值按「全部」处理，避免拼错参数返回空表让人以为没数据。 */
    private static final Set<String> SALES_ITEM_CATEGORIES = Set.of("PRODUCT", "SERVICE", "PACKAGE", "ROOM_FEE", "ADD_ON");

    private static String normalizeItemCategory(String itemType) {
        if (itemType == null || itemType.isBlank()) {
            return null;
        }
        String normalized = itemType.trim().toUpperCase(Locale.ROOT);
        return SALES_ITEM_CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static int clampTopN(Integer topN) {
        int value = topN == null ? 20 : topN;
        return Math.max(1, Math.min(value, 200));
    }

    private static String currencyKey(Map<String, Object> row) {
        String currency = currencyVal(row);
        return currency == null ? "" : currency;
    }

    /** 明细页的支付构成：只查当前页的订单 id（空页不发查询）。 */
    private Map<Long, SalesReport.PaymentComposition> loadPaymentCompositions(Long tenantId,
            List<Map<String, Object>> rows) {
        List<Long> orderIds = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            Long orderId = longOrNull(m, "order_id");
            if (orderId != null) {
                orderIds.add(orderId);
            }
        }
        Map<Long, SalesReport.PaymentComposition> result = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        for (Long orderId : orderIds) {
            result.put(orderId, SalesReport.PaymentComposition.empty());
        }
        for (Map<String, Object> m : reportMapper.selectOrderPaymentComposition(tenantId, orderIds)) {
            Long orderId = longOrNull(m, "order_id");
            SalesReport.PaymentComposition current = result.get(orderId);
            if (current == null) {
                continue;
            }
            BigDecimal amount = decimalVal(m, "payment_amount");
            result.put(orderId, current.plus(PayChannel.of(stringVal(m, "provider")), amount));
        }
        return result;
    }

    /** 明细状态过滤：空白 = 不筛（与统计同口径）；大小写不敏感；非法值 400。 */
    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(status)) {
            throw new ApiException(400, "ORDER_STATUS_INVALID", "单据状态不合法: " + raw.trim());
        }
        return status;
    }

    private static int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static Long requireTenant() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return tenantId;
    }

    // —— 内部聚合/键 ——
    //
    // 币种是聚合键的一部分（规范 §3.6）：同一门店同一统计桶的不同币种金额必须分行返回，
    // 绝不相加成一个数字——否则就是把 USD 当 CNY 求和，且切换币种后历史报表会被重新解释。

    /** 员工键：类型 + 员工 + 门店 + 统计桶 + 币种（桶必须进键，否则不同桶的数字会被并成一行）。 */
    private static String employeeKey(String type, Map<String, Object> m, Scope scope) {
        return type + "|" + (m.get("employee_id") == null ? "0" : m.get("employee_id").toString())
                + "|" + (m.get("store_id") == null ? "0" : m.get("store_id").toString())
                + "|" + scope.bucketOf(dateVal(m, "business_date")).start()
                + "|" + currencyVal(m);
    }

    /** 通用桶键：门店 + 统计桶 + 币种（经营/支付/毛利/销售共用）。 */
    private static String bucketKey(Map<String, Object> m, Scope scope) {
        return (m.get("store_id") == null ? "0" : m.get("store_id").toString())
                + "|" + scope.bucketOf(dateVal(m, "business_date")).start()
                + "|" + currencyVal(m);
    }

    /**
     * 资源利用率的分母键：门店 + 包厢 + **营业日**。
     *
     * <p>刻意不进统计桶：利用率是「该包厢这一天用得怎么样」，按周/月/年看时逐行的分母仍是**当日** 24h，
     * 否则桶变大分母跟着变大，同一包厢同一天的比率会随查询粒度漂移。
     */
    private static String roomDayKey(Map<String, Object> m) {
        return (m.get("store_id") == null ? "0" : m.get("store_id").toString())
                + "|" + (m.get("resource_id") == null ? "0" : m.get("resource_id").toString())
                + "|" + stringVal(m, "business_date");
    }

    /**
     * 该包厢该营业日的利用率 = 会话时长合计 ÷ {@link #BUSINESS_DAY_AVAILABLE_SECONDS}（比例值，非百分比）。
     *
     * <p>定点除法只在应用层做一处，SQL 不出现乘除；超过 1（同一包厢同日会话重叠等脏数据）**不封顶**，
     * 宁可让使用方看到 &gt;100% 去查数据，也不要截断成一个看起来正常的数字。
     */
    private static BigDecimal utilizationRate(long roomDaySeconds) {
        return BigDecimal.valueOf(roomDaySeconds)
                .divide(BigDecimal.valueOf(BUSINESS_DAY_AVAILABLE_SECONDS), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 记录自身的币种快照；缺省（历史空值/未知值）按 USD，与全局缺省一致。 */
    private static String currencyVal(Map<String, Object> m) {
        return Currency.parse(stringVal(m, "currency_code")).code();
    }

    /**
     * 按 (桶, 门店, 币种) 排序。
     *
     * <p>桶一定非空（由营业日算出），门店/币种可能为空，因此末位用 {@link #CURRENCY_ORDER}
     * 兜住 null —— 历史实现在这里用 {@code Comparator.comparing(Row::currencyCode)}，
     * 两行同 (门店, 桶) 不同币种时直接 NPE（见 {@link #payments} 的说明）。
     */
    private static <T> void sortByBucket(List<T> rows, java.util.function.Function<T, TimeBucket> bucket,
            java.util.function.Function<T, Long> storeId, java.util.function.Function<T, String> currency) {
        rows.sort(Comparator.comparing(bucket)
                .thenComparing(row -> {
                    Long id = storeId.apply(row);
                    return id == null ? 0L : id;
                })
                .thenComparing(currency, CURRENCY_ORDER));
    }

    private static EmployeeAgg cashier(Map<String, Object> m, Scope scope) {
        EmployeeAgg agg = new EmployeeAgg();
        agg.type = "CASHIER";
        agg.employeeId = longOrNull(m, "employee_id");
        agg.storeId = longOrNull(m, "store_id");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    private static EmployeeAgg server(Map<String, Object> m, Scope scope) {
        EmployeeAgg agg = new EmployeeAgg();
        agg.type = "SERVER";
        agg.employeeId = longOrNull(m, "employee_id");
        agg.employeeName = stringVal(m, "employee_name");
        agg.storeId = longOrNull(m, "store_id");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    private static OperationsAgg operations(Map<String, Object> m, Scope scope) {
        OperationsAgg agg = new OperationsAgg();
        agg.storeId = longOrNull(m, "store_id");
        agg.storeName = stringVal(m, "store_name");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    private static PaymentsAgg payments(Map<String, Object> m, Scope scope) {
        PaymentsAgg agg = new PaymentsAgg();
        agg.storeId = longOrNull(m, "store_id");
        agg.storeName = stringVal(m, "store_name");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    private static GrossProfitAgg grossProfit(Map<String, Object> m, Scope scope) {
        GrossProfitAgg agg = new GrossProfitAgg();
        agg.storeId = longOrNull(m, "store_id");
        agg.storeName = stringVal(m, "store_name");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    private static SalesAgg sales(Map<String, Object> m, Scope scope) {
        SalesAgg agg = new SalesAgg();
        agg.storeId = longOrNull(m, "store_id");
        agg.storeName = stringVal(m, "store_name");
        agg.bucket = scope.bucketOf(dateVal(m, "business_date"));
        agg.currencyCode = currencyVal(m);
        return agg;
    }

    /** 单币种时返回该币种，混币种（或没有行）时返回 null（调用方据 mixedCurrency 决定展示方式）。 */
    private static String singleCurrency(List<String> currencies) {
        Set<String> distinct = new LinkedHashSet<>(currencies);
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    // —— 取值 ——

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString());
    }

    private static Long longOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString());
    }

    private static BigDecimal decimalVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(v.toString());
    }

    private static String stringVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static LocalDate dateVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        String s = v.toString();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    // —— 聚合中间态 ——

    private static final class EmployeeAgg {
        String type;
        Long employeeId;
        String employeeName;
        Long storeId;
        String currencyCode;
        TimeBucket bucket;
        long openOrderCount;
        long serviceSeconds;
        BigDecimal collectedAmount = BigDecimal.ZERO;
        long addOnCount;
    }

    private static final class OperationsAgg {
        Long storeId;
        String storeName;
        TimeBucket bucket;
        String currencyCode;
        long orderCount;
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        long refundCount;
        BigDecimal refund = BigDecimal.ZERO;
    }

    private static final class PaymentsAgg {
        Long storeId;
        String storeName;
        TimeBucket bucket;
        String currencyCode;
        long collectionCount;
        BigDecimal collected = BigDecimal.ZERO;
        long refundCount;
        BigDecimal refund = BigDecimal.ZERO;
    }

    /** 库存成本与毛利的聚合中间态（收入与成本两条查询按同一 (门店, 桶, 币种) 键合并）。 */
    private static final class GrossProfitAgg {
        Long storeId;
        String storeName;
        TimeBucket bucket;
        String currencyCode;
        BigDecimal revenueAmount = BigDecimal.ZERO;
        BigDecimal costAmount = BigDecimal.ZERO;
        BigDecimal soldQuantity = BigDecimal.ZERO;
        BigDecimal uncostedQuantity = BigDecimal.ZERO;
    }

    /** 销售报表统计的聚合中间态（订单/退款/收款三条查询按同一 (门店, 桶, 币种) 键合并）。 */
    private static final class SalesAgg {
        Long storeId;
        String storeName;
        TimeBucket bucket;
        String currencyCode;
        long orderCount;
        BigDecimal salesAmount = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal paidAmount = BigDecimal.ZERO;
        long refundCount;
        BigDecimal refundAmount = BigDecimal.ZERO;
        long voidedCount;
        BigDecimal voidedAmount = BigDecimal.ZERO;
        long paymentCount;
        BigDecimal collectedAmount = BigDecimal.ZERO;
        BigDecimal cashAmount = BigDecimal.ZERO;
        BigDecimal onlineAmount = BigDecimal.ZERO;
        BigDecimal walletAmount = BigDecimal.ZERO;
        BigDecimal pointsAmount = BigDecimal.ZERO;
        BigDecimal otherAmount = BigDecimal.ZERO;
    }

    /** 支付方式归类（「现金/线上/储值币/积分」构成；未识别的取值归 OTHER，不静默丢弃）。 */
    private enum PayChannel {
        CASH, ONLINE, WALLET, POINTS, OTHER;

        static PayChannel of(String provider) {
            if (provider == null || provider.isBlank()) {
                return OTHER;
            }
            String code = provider.trim().toUpperCase(Locale.ROOT);
            if ("CASH".equals(code)) {
                return CASH;
            }
            if ("WALLET".equals(code)) {
                return WALLET;
            }
            if ("POINT".equals(code) || "POINTS".equals(code)) {
                return POINTS;
            }
            return ONLINE_PROVIDERS.contains(code) ? ONLINE : OTHER;
        }
    }

    /**
     * 报表取数范围 + 粒度（一次解析，贯穿所有报表）。
     *
     * <p>{@code from}/{@code to} 只解析**一次**（{@link TimeRangeParams#parse}，闭区间 + 倒挂/非法即 400），
     * 然后把「营业日区间」换算成 SQL 用的存储值半开区间
     * {@code [windowFrom(fromDay), windowToExclusive(toDay))}，并把营业日平移量
     * （{@link ReportTimeBuckets#BUSINESS_DAY_SHIFT_SECONDS}）一并交给 Mapper —— 切日口径只有一处定义。
     */
    private record Scope(LocalDate fromDay, LocalDate toDay, LocalDateTime from, LocalDateTime to,
                         ReportGranularity granularity, int shiftSeconds) {

        static Scope of(String from, String to, String granularity) {
            TimeRange range = TimeRangeParams.parse(from, to);
            ReportGranularity parsed = ReportGranularity.parse(granularity);
            LocalDate today = ReportTimeBuckets.businessToday();
            LocalDate fromDay = range.hasFrom() ? range.fromInclusive().toLocalDate()
                    : today.minusDays(DEFAULT_RANGE_DAYS);
            LocalDate toDay = range.hasTo() ? range.toInclusive().toLocalDate() : today;
            return new Scope(fromDay, toDay, ReportTimeBuckets.windowFrom(fromDay),
                    ReportTimeBuckets.windowToExclusive(toDay), parsed,
                    ReportTimeBuckets.BUSINESS_DAY_SHIFT_SECONDS);
        }

        TimeBucket bucketOf(LocalDate businessDay) {
            return ReportTimeBuckets.bucket(granularity, businessDay);
        }

        String granularityCode() {
            return granularity.name();
        }
    }

    // —— 响应模型 ——
    //
    // 每一行金额都带同级 currencyCode（记录自身的币种快照，缺省 USD）：前端必须按该币种渲染符号，
    // 同一门店同一个桶可能出现多行（不同币种），禁止把多行金额直接相加。
    // 每一行的 bucket 给出桶的起止与展示标签（如 2026-09-19 / 2026年第38周 / 2026-09 / 2026年），
    // 桶标签由服务端统一生成（周数不在前端算），前端只做展示。

    public record EmployeePerformanceReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            List<Row> rows) {
        public record Row(String employeeType, Long employeeId, String employeeName, Long storeId,
                TimeBucket bucket, long openOrderCount, long serviceSeconds, BigDecimal collectedAmount,
                String currencyCode, long addOnCount) {}
    }

    public record OperationsReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            List<Row> rows) {
        public record Row(Long storeId, String storeName, TimeBucket bucket, String currencyCode,
                long orderCount, BigDecimal receivableAmount, BigDecimal paidAmount, BigDecimal refundAmount,
                BigDecimal discountAmount, BigDecimal averageTicketAmount) {}
    }

    public record PaymentsReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            List<Row> rows) {
        public record Row(Long storeId, String storeName, TimeBucket bucket, String currencyCode,
                long collectionCount, BigDecimal collectedAmount, long refundCount, BigDecimal refundAmount) {}
    }

    /**
     * 资源利用率报表（KTV 包厢）——**一次开台一行**，不是按包厢/桶聚合的一行。
     *
     * <p>同一包厢同一营业日开台多次就有多行，每行只描述**那一次消费**；时间桶（{@code bucket}）仍按
     * {@code granularity} 给出，供「按周/月/年看」时归组，但**不改变行粒度、也不改变利用率的分母口径**。
     *
     * @param utilizationBasis 利用率口径标识（当前恒为 {@code ROOM_DAY_SESSION_SUM_OVER_24H}：
     *                         该包厢该营业日会话时长合计 ÷ 24 小时）
     */
    public record ResourceUtilizationReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            String utilizationBasis, List<Row> rows) {

        /**
         * 一次开台（一次消费）。
         *
         * @param businessDate    本次开台所属**营业日**（按 {@code openedAt} 归属，与 {@code bucket} 同一口径）
         * @param sessionId       KTV 会话 ID（{@code ord_ktv_session.id}，一行一次开台）
         * @param openedAt        开台时间（存储值 ISO，营业日平移前的原值，口径与销售明细的 {@code createdAt} 一致）
         * @param closedAt        结台时间（存储值 ISO）；尚未结台为 {@code null}
         * @param durationSeconds 本次开台时长（秒）= {@code closedAt − openedAt − pausedSeconds}；
         *                        **未结台记 0**，缺失/负值兜底为 0；同一包厢多次开台各算各的，不做任何求和
         * @param sessionStatus   会话状态（{@code OPEN}/{@code PAUSED}/{@code CLOSED}），区分「进行中」与「已完成」
         * @param turnoverCount   本次开台**是否已完成**：{@code CLOSED}=1，其它=0。
         *                        同一包厢同一营业日各行之和 = 当日完成场次数（翻台次数）——
         *                        **不是** RELEASED 占用行数，不得改回按占用窗口统计
         * @param turnoverRate    该包厢**该营业日**利用率（比例值，非百分比）= 当日会话时长合计 ÷ 24 小时；
         *                        同一包厢同营业日的每一行给同一个值，分子与 {@code durationSeconds} 同源
         */
        public record Row(Long storeId, String storeName, Long resourceId, String resourceName, TimeBucket bucket,
                LocalDate businessDate, Long sessionId, String openedAt, String closedAt, long durationSeconds,
                String sessionStatus, long turnoverCount, BigDecimal turnoverRate) {}
    }

    /**
     * 库存成本与毛利报表（按币种分组）。
     *
     * @param granularity  统计粒度（DAY/WEEK/MONTH/YEAR）
     * @param currencyCode 单币种时的该币种；混币种时 null，调用方必须逐行按行内 currencyCode 渲染
     * @param mixedCurrency 是否存在多种币种；true 时**禁止**把多行金额相加
     * @param costBasis 成本口径标识（当前恒为 PERIOD_END_MOVING_AVERAGE）
     */
    public record InventoryGrossProfitReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            String currencyCode, boolean mixedCurrency, String costBasis, List<Row> rows) {
        /**
         * @param revenueAmount     期间收入（订单明细 total_amount 之和，最小货币单位）
         * @param costAmount        期间成本（净售出数量 × 移动加权平均成本，最小货币单位）
         * @param grossProfitAmount 毛利 = 收入 − 成本
         * @param grossMarginRate   毛利率 = 毛利 / 收入（收入为 0 时为 null，避免 0 除与「0% 毛利」的歧义）
         * @param soldQuantity      期间净售出数量（CONSUME − REVERSE，计量单位）
         * @param uncostedQuantity  其中平均成本缺失（≤ 0）的数量，成本覆盖度的显式标注
         */
        public record Row(Long storeId, String storeName, TimeBucket bucket, String currencyCode,
                BigDecimal revenueAmount, BigDecimal costAmount, BigDecimal grossProfitAmount,
                BigDecimal grossMarginRate, BigDecimal soldQuantity, BigDecimal uncostedQuantity) {}
    }

    /**
     * 销售报表（统计 + 明细）。
     *
     * @param granularity   统计粒度（DAY/WEEK/MONTH/YEAR）
     * @param businessZone  营业日所用时区（响应显式声明，避免调用方按 UTC 自然日理解桶标签）
     * @param status        明细采用的状态过滤（null = 与统计同口径，排除 CANCELLED/VOIDED）
     * @param currencyCode  单币种时的该币种；混币种时 null
     * @param mixedCurrency 是否混币种；true 时**禁止**把多行金额相加（前端显示 MIXED_CURRENCY_NOTICE）
     */
    public record SalesReport(Instant dataAsOf, LocalDate from, LocalDate to, String granularity,
            String businessZone, String status, String currencyCode, boolean mixedCurrency,
            List<BucketRow> buckets, SalesDetails details) {

        /**
         * 统计行（一个时间桶一行；同桶多币种会拆成多行）。
         *
         * @param salesAmount     销售额 = 有效单据 {@code ord_order.total_amount} 之和（排除取消/作废）
         * @param averageTicketAmount 客单价 = 销售额 / 订单数（订单数为 0 时为 0）
         * @param refundAmount    退款金额（{@code pay_refund} 终态 REFUNDED 的审批金额之和）
         * @param voidedAmount    作废金额（{@code status='VOIDED'} 单据的 {@code total_amount} 之和）
         * @param collectedAmount 收款金额（{@code pay_transaction} SUCCEEDED 流水之和）
         * @param cashAmount      收款构成之现金
         * @param onlineAmount    收款构成之线上（ALIPAY/WECHAT/STRIPE）
         * @param walletAmount    收款构成之储值币（WALLET）
         * @param pointsAmount    收款构成之积分（POINT）
         * @param otherAmount     收款构成之其它：新增渠道未归类时**显式暴露**，保证构成之和可核对
         */
        public record BucketRow(Long storeId, String storeName, TimeBucket bucket, String currencyCode,
                long orderCount, BigDecimal salesAmount, BigDecimal discountAmount, BigDecimal paidAmount,
                BigDecimal averageTicketAmount, long refundCount, BigDecimal refundAmount, long voidedCount,
                BigDecimal voidedAmount, long paymentCount, BigDecimal collectedAmount, BigDecimal cashAmount,
                BigDecimal onlineAmount, BigDecimal walletAmount, BigDecimal pointsAmount, BigDecimal otherAmount) {}

        /**
         * 明细分页（Page 信封，与后台其它列表同一约定）。
         *
         * @param total 满足**同一**过滤条件的单据总数（等于统计侧订单数之和）
         */
        public record SalesDetails(List<DetailRow> records, long total, long current, long size) {}

        /**
         * 明细行（销售单据）。
         *
         * @param createdAt    存储值（UTC 墙钟）的 ISO 形态，与订单页时间口径一致
         * @param businessDate 该单据归属的**营业日**（与统计桶同源；跨零点通宵场次会与 createdAt 的日期不同）
         * @param roomName     包厢名快照（该订单第一条 KTV 会话；无会话则为 null）
         */
        public record DetailRow(Long orderId, String orderNo, String createdAt, LocalDate businessDate,
                Long storeId, String storeName, String roomName, String status, String currencyCode,
                BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount, BigDecimal paidAmount,
                BigDecimal refundableAmount, PaymentComposition payment) {}

        /** 单张单据的支付构成（成功流水按方式归类；币种不同的构成不会混算——跨币种单据在库里本来就不允许）。 */
        public record PaymentComposition(BigDecimal cashAmount, BigDecimal onlineAmount, BigDecimal walletAmount,
                BigDecimal pointsAmount, BigDecimal otherAmount) {

            static PaymentComposition empty() {
                return new PaymentComposition(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO);
            }

            PaymentComposition plus(PayChannel channel, BigDecimal amount) {
                return switch (channel) {
                    case CASH -> new PaymentComposition(cashAmount.add(amount), onlineAmount, walletAmount,
                            pointsAmount, otherAmount);
                    case ONLINE -> new PaymentComposition(cashAmount, onlineAmount.add(amount), walletAmount,
                            pointsAmount, otherAmount);
                    case WALLET -> new PaymentComposition(cashAmount, onlineAmount, walletAmount.add(amount),
                            pointsAmount, otherAmount);
                    case POINTS -> new PaymentComposition(cashAmount, onlineAmount, walletAmount,
                            pointsAmount.add(amount), otherAmount);
                    default -> new PaymentComposition(cashAmount, onlineAmount, walletAmount, pointsAmount,
                            otherAmount.add(amount));
                };
            }
        }
    }
}
