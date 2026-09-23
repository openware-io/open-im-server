package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 经营/业绩/资源利用率只读报表（E6 完成定义：只读聚合，标注数据时点，不建领域表）。
 * 外部路径 /api/v1/reports/**（Gateway StripPrefix=2 → /reports/**）。
 *
 * <p><b>金额单位口径（实证结论）</b>：金额一律为**最小货币单位**（CNY 分 / USD cent），与
 * {@code ord_catalog_item.unit_price}（注释「最小货币单位：分」）、{@code ord_product.sale_price}（「销售单价（分）」）、
 * {@code BillApplicationService#toMinor}（「订单域金额已统一按「分」存储，仅做类型收敛」）同源；
 * 本项目 <b>没有</b>任何「decimal(20,6) 存元」的订单金额列（旧注释「单位元」为错误描述，已更正）。
 *
 * <p><b>币种口径（16_CURRENCY_CONVENTIONS §5）</b>：跨记录聚合必须按币种分组，禁止把不同币种静默求和。
 * 每行带自己的 {@code currencyCode}（已结算订单取订单快照）；信封上再给出单币种 {@code currencyCode}
 * 与 {@code mixedCurrency} 标记，混币种时 {@code currencyCode=null} 且 {@code mixedCurrency=true}。
 */
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final OrderMapper orderMapper;
    private final KtvSessionMapper ktvSessionMapper;

    public ReportController(OrderMapper orderMapper, KtvSessionMapper ktvSessionMapper) {
        this.orderMapper = orderMapper;
        this.ktvSessionMapper = ktvSessionMapper;
    }

    /** 经营报表：按「门店 + 币种」聚合订单数、应收、实收（跨币种不合并，逐币种一行）。 */
    @GetMapping("/store-operation")
    public Map<String, Object> storeOperation(@RequestParam LocalDate businessDate) {
        Long tenantId = requireTenant();
        List<OrderPo> orders = ordersOfBusinessDate(tenantId, businessDate);
        Map<String, AmountRow> byStoreCurrency = new LinkedHashMap<>();
        for (OrderPo o : orders) {
            String currency = Currency.parse(o.getCurrencyCode()).code();
            AmountRow row = byStoreCurrency.computeIfAbsent(o.getStoreId() + "|" + currency,
                    key -> new AmountRow(o.getStoreId(), currency));
            row.orderCount++;
            row.receivable = row.receivable.add(nz(o.getTotalAmount()));
            row.collected = row.collected.add(nz(o.getPaidAmount()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byStoreCurrency.values().forEach(row -> rows.add(Map.of(
                "storeId", row.id, "currencyCode", row.currency, "orderCount", row.orderCount,
                "receivable", row.receivable, "collected", row.collected)));
        return reportEnvelope(businessDate, rows, currenciesOf(byStoreCurrency.values()));
    }

    /**
     * 资源利用率：按包厢聚合占用时长（秒）与翻台次数；同时带出该包厢最近一次会话的「人数 / 服务人员」
     * （房态看板要在一行里看到包厢、人数与服务人员，避免前端再按 sessionId 逐条回查）。
     * 纯时长/次数指标，无金额，因此不涉及币种聚合。
     */
    @GetMapping("/resource-utilization")
    public Map<String, Object> resourceUtilization(@RequestParam LocalDate businessDate) {
        Long tenantId = requireTenant();
        List<KtvSessionPo> sessions = ktvSessionMapper.selectList(new LambdaQueryWrapper<KtvSessionPo>()
                .eq(KtvSessionPo::getTenantId, tenantId)
                .ge(KtvSessionPo::getOpenedAt, businessDate.atStartOfDay())
                .lt(KtvSessionPo::getOpenedAt, businessDate.plusDays(1).atStartOfDay()));
        Map<Long, long[]> byRoom = new LinkedHashMap<>();
        Map<Long, KtvSessionPo> latestSession = new LinkedHashMap<>();
        for (KtvSessionPo s : sessions) {
            long seconds = s.getOpenedAt() != null && s.getClosedAt() != null
                    ? java.time.Duration.between(s.getOpenedAt(), s.getClosedAt()).getSeconds() : 0L;
            byRoom.merge(s.getRoomResourceId(), new long[]{seconds, 1}, (a, b) -> { a[0] += b[0]; a[1] += b[1]; return a; });
            latestSession.merge(s.getRoomResourceId(), s, (a, b) ->
                    a.getId() != null && b.getId() != null && b.getId() > a.getId() ? b : a);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byRoom.forEach((roomId, v) -> {
            KtvSessionPo latest = latestSession.get(roomId);
            // 可变 Map：partySize/serverName 允许为 null（Map.of 不接受 null 值）。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roomResourceId", roomId);
            row.put("occupiedSeconds", v[0]);
            row.put("turnover", v[1]);
            row.put("partySize", latest == null ? null : latest.getPartySize());
            row.put("serverId", latest == null ? null : latest.getServerId());
            row.put("serverName", latest == null ? null : latest.getServerName());
            rows.add(row);
        });
        return Map.of("businessDate", businessDate.toString(), "asOf", OffsetDateTime.now().toString(),
                "items", rows);
    }

    /** 员工业绩（D-13）：按「开单员工 + 币种」聚合开单数/应收/实收（服务人员/提成维度待 E 后续补）。 */
    @GetMapping("/employee-performance")
    public Map<String, Object> employeePerformance(@RequestParam LocalDate businessDate) {
        Long tenantId = requireTenant();
        List<OrderPo> orders = ordersOfBusinessDate(tenantId, businessDate);
        Map<String, AmountRow> byEmpCurrency = new LinkedHashMap<>();
        for (OrderPo o : orders) {
            Long emp = o.getCreatedBy() == null ? 0L : o.getCreatedBy();
            String currency = Currency.parse(o.getCurrencyCode()).code();
            AmountRow row = byEmpCurrency.computeIfAbsent(emp + "|" + currency,
                    key -> new AmountRow(emp, currency));
            row.orderCount++;
            row.receivable = row.receivable.add(nz(o.getTotalAmount()));
            row.collected = row.collected.add(nz(o.getPaidAmount()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byEmpCurrency.values().forEach(row -> rows.add(Map.of(
                "employeeId", row.id, "currencyCode", row.currency, "orderCount", row.orderCount,
                "receivable", row.receivable, "collected", row.collected)));
        return reportEnvelope(businessDate, rows, currenciesOf(byEmpCurrency.values()));
    }

    private List<OrderPo> ordersOfBusinessDate(Long tenantId, LocalDate businessDate) {
        return orderMapper.selectList(new LambdaQueryWrapper<OrderPo>()
                .eq(OrderPo::getTenantId, tenantId)
                .ge(OrderPo::getCreatedAt, businessDate.atStartOfDay())
                .lt(OrderPo::getCreatedAt, businessDate.plusDays(1).atStartOfDay()));
    }

    /**
     * 报表信封：显式标注币种标识（§5「列表/报表混排必须显示币种标识；跨币种聚合要么拒绝要么显式标注」）。
     * 单币种时 {@code currencyCode} 即该币种（前端可直接用它渲染符号）；混币种时 {@code currencyCode=null}
     * 且 {@code mixedCurrency=true}，调用方必须逐行按行内 {@code currencyCode} 渲染，不得当成一个合计。
     */
    private static Map<String, Object> reportEnvelope(LocalDate businessDate, List<Map<String, Object>> rows,
                                                      Set<String> currencies) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("businessDate", businessDate.toString());
        result.put("asOf", OffsetDateTime.now().toString());
        result.put("currencyCode", currencies.size() == 1 ? currencies.iterator().next() : null);
        result.put("mixedCurrency", currencies.size() > 1);
        result.put("items", rows);
        return result;
    }

    private static Set<String> currenciesOf(Collection<AmountRow> rows) {
        Set<String> currencies = new LinkedHashSet<>();
        rows.forEach(row -> currencies.add(row.currency));
        return currencies;
    }

    /**
     * 租户上下文：缺失时必须抛 401 + {code,message}，不能返回 HTTP 200 + 错误载荷
     * （否则调用方按状态码判断成功会把「没有上下文」误读成空报表）。
     */
    private static Long requireTenant() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return tenantId;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** 「同一门店/员工 + 同一币种」的金额累加器（可变，供聚合循环使用）。 */
    private static final class AmountRow {
        private final Long id;
        private final String currency;
        private long orderCount;
        private BigDecimal receivable = BigDecimal.ZERO;
        private BigDecimal collected = BigDecimal.ZERO;

        private AmountRow(Long id, String currency) {
            this.id = id;
            this.currency = currency;
        }
    }
}
