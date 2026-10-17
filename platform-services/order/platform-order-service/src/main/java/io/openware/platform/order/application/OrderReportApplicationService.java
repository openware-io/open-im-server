package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营/业绩/资源利用率只读报表（E6：只读聚合，标注数据时点，不建领域表）。
 *
 * <p><b>金额单位口径</b>：金额一律为最小货币单位（CNY 分 / USD cent），与订单域其它金额列同源。
 *
 * <p><b>币种口径（16_CURRENCY_CONVENTIONS §5）</b>：跨记录聚合必须按币种分组，禁止把不同币种静默求和。
 * 每行带自己的 {@code currencyCode}；信封上给出单币种 {@code currencyCode} 与 {@code mixedCurrency} 标记。
 */
@Service
@RequiredArgsConstructor
public class OrderReportApplicationService {
  private final OrderRepository orderRepository;
  private final KtvSessionRepository ktvSessionRepository;

  /** 经营报表：按「门店 + 币种」聚合订单数、应收、实收（跨币种不合并，逐币种一行）。 */
  @Transactional(readOnly = true)
  public Map<String, Object> storeOperation(LocalDate businessDate) {
    Long tenantId = requireTenant();
    List<Persisted<Order>> orders = ordersOfBusinessDate(tenantId, businessDate);
    Map<String, AmountRow> byStoreCurrency = new LinkedHashMap<>();
    for (Persisted<Order> row : orders) {
      Order o = row.aggregate();
      String currency = Currency.parse(o.currencyCode()).code();
      AmountRow amountRow = byStoreCurrency.computeIfAbsent(o.storeId() + "|" + currency,
          key -> new AmountRow(o.storeId(), currency));
      amountRow.orderCount++;
      amountRow.receivable = amountRow.receivable.add(nz(o.totalAmount()));
      amountRow.collected = amountRow.collected.add(nz(o.paidAmount()));
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    byStoreCurrency.values().forEach(row -> rows.add(Map.of(
        "storeId", row.id, "currencyCode", row.currency, "orderCount", row.orderCount,
        "receivable", row.receivable, "collected", row.collected)));
    return reportEnvelope(businessDate, rows, currenciesOf(byStoreCurrency.values()));
  }

  /**
   * 资源利用率：按包厢聚合占用时长（秒）与翻台次数；同时带出该包厢最近一次会话的「人数 / 服务人员」。
   * 纯时长/次数指标，无金额，不涉及币种聚合。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> resourceUtilization(LocalDate businessDate) {
    Long tenantId = requireTenant();
    // 时间条件是半开区间 [当天起点, 次日起点)：直接落在 opened_at 列上，不用函数包裹（索引可用）。
    List<KtvSession> sessions = ktvSessionRepository.findOpenedBetween(tenantId,
        businessDate.atStartOfDay(), businessDate.plusDays(1).atStartOfDay());
    Map<Long, long[]> byRoom = new LinkedHashMap<>();
    Map<Long, KtvSession> latestSession = new LinkedHashMap<>();
    for (KtvSession s : sessions) {
      long seconds = s.openedAt() != null && s.closedAt() != null
          ? java.time.Duration.between(s.openedAt(), s.closedAt()).getSeconds() : 0L;
      byRoom.merge(s.roomResourceId(), new long[]{seconds, 1},
          (a, b) -> { a[0] += b[0]; a[1] += b[1]; return a; });
      latestSession.merge(s.roomResourceId(), s, (a, b) ->
          a.id() != null && b.id() != null && b.id() > a.id() ? b : a);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    byRoom.forEach((roomId, v) -> {
      KtvSession latest = latestSession.get(roomId);
      // 可变 Map：partySize/serverName 允许为 null（Map.of 不接受 null 值）。
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("roomResourceId", roomId);
      row.put("occupiedSeconds", v[0]);
      row.put("turnover", v[1]);
      row.put("partySize", latest == null ? null : latest.partySize());
      row.put("serverId", latest == null ? null : latest.serverId());
      row.put("serverName", latest == null ? null : latest.serverName());
      rows.add(row);
    });
    return Map.of("businessDate", businessDate.toString(), "asOf", OffsetDateTime.now().toString(),
        "items", rows);
  }

  /** 员工业绩（D-13）：按「开单员工 + 币种」聚合开单数/应收/实收。 */
  @Transactional(readOnly = true)
  public Map<String, Object> employeePerformance(LocalDate businessDate) {
    Long tenantId = requireTenant();
    List<Persisted<Order>> orders = ordersOfBusinessDate(tenantId, businessDate);
    Map<String, AmountRow> byEmpCurrency = new LinkedHashMap<>();
    for (Persisted<Order> persisted : orders) {
      Order o = persisted.aggregate();
      // 「开单员工」是记账列 created_by：审计列不进领域模型，由适配器随行读回（没有则记 0 = 系统）。
      Long createdBy = persisted.audit() == null ? null : persisted.audit().createdBy();
      Long emp = createdBy == null ? 0L : createdBy;
      String currency = Currency.parse(o.currencyCode()).code();
      AmountRow amountRow = byEmpCurrency.computeIfAbsent(emp + "|" + currency,
          key -> new AmountRow(emp, currency));
      amountRow.orderCount++;
      amountRow.receivable = amountRow.receivable.add(nz(o.totalAmount()));
      amountRow.collected = amountRow.collected.add(nz(o.paidAmount()));
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    byEmpCurrency.values().forEach(row -> rows.add(Map.of(
        "employeeId", row.id, "currencyCode", row.currency, "orderCount", row.orderCount,
        "receivable", row.receivable, "collected", row.collected)));
    return reportEnvelope(businessDate, rows, currenciesOf(byEmpCurrency.values()));
  }

  /**
   * 某租户某**营业日**的订单：半开区间 {@code [当天起点, 次日起点)}，直接落在 {@code created_at} 上。
   * 返回记账快照：员工业绩的「开单员工」就是 {@code created_by}。
   */
  private List<Persisted<Order>> ordersOfBusinessDate(Long tenantId, LocalDate businessDate) {
    return orderRepository.list(OrderQuery.all().withTenant(tenantId)
        .from(businessDate.atStartOfDay())
        .before(businessDate.plusDays(1).atStartOfDay()));
  }

  /**
   * 报表信封：显式标注币种标识。单币种时 {@code currencyCode} 即该币种；混币种时
   * {@code currencyCode=null} 且 {@code mixedCurrency=true}，调用方必须逐行按行内币种渲染。
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

  /** 租户上下文：缺失时抛 401，不能返回 200 + 错误载荷。 */
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
