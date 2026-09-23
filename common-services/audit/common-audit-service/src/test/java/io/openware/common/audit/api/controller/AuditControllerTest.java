package io.openware.common.audit.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.audit.api.dto.AuditPageView;
import io.openware.common.audit.api.dto.AuditQueryRequest;
import io.openware.common.audit.application.service.AuditQueryApplicationService;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.time.TimeRangeParams;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 查询接口入参契约回归：page/pageSize/时间/枚举非法一律 400（不能落到 500 或静默忽略）。
 *
 * <p><b>时间参数有两套，优先级明确</b>：
 * <ol>
 *   <li>统一参数 {@code from}/{@code to}（与全仓其它列表同一 {@code TIME_RANGE_INVALID} 错误码）——
 *       日期形态的 {@code from} → 当天 {@code 00:00:00}；{@code to} → **次日 {@code 00:00:00}**，
 *       因为本模块仓储的时间条件是左闭右开（{@code occurred_at >= from AND occurred_at < to}），
 *       排他上界必须取次日零点，否则「查到 9-30 为止」会漏掉当天最后一毫秒；</li>
 *   <li>既有参数 {@code fromAt}/{@code toAt} 与别名 {@code occurredFrom}/{@code occurredTo} ——
 *       继续走 {@code AuditTimeParser}（支持 ISO-8601 / epoch），**行为不变**，只为兼容旧调用方。</li>
 * </ol>
 */
class AuditControllerTest {

  private final CapturingQueryService service = new CapturingQueryService();
  private final AuditController controller = new AuditController(service);

  @Test
  void parsesPagingTimeRangeAndAliases() {
    invoke(new Query()
        .page("2").pageSize("50").tenantId(1001L).organizationId(20L).storeId(30L).operatorId(77L)
        .operatorKeyword("张三").action("order.settle").resourceType("ord_order").resourceId("9")
        .result("failure").operatorType("tenant").requestId("req-1").traceId("trace-1")
        .fromAt("2026-09-01 00:00:00").toAt("2026-09-30 00:00:00").order("asc"));

    AuditQueryRequest captured = service.captured;
    assertEquals(2, captured.page());
    assertEquals(50, captured.pageSize());
    assertEquals(1001L, captured.tenantId());
    assertEquals("FAILURE", captured.result());
    assertEquals("TENANT", captured.operatorType());
    assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), captured.from());
    assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), captured.to());
    assertTrue(captured.ascending());
    assertEquals(false, captured.skipCount());
  }

  /** 既有别名保持原样：{@code fromAt} 缺席时用 {@code occurredFrom}，且不改变既有的左闭右开口径。 */
  @Test
  void occurredFromAliasIsAcceptedWhenFromAtIsAbsent() {
    invoke(new Query().occurredFrom("2026-09-01").occurredTo("2026-09-02"));

    assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), service.captured.from());
    assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0), service.captured.to());
  }

  @Test
  void defaultsToDescendingOrderPageOneAndTwentyItems() {
    invoke(new Query());

    assertEquals(1, service.captured.page());
    assertEquals(20, service.captured.pageSize());
    assertEquals(false, service.captured.ascending());
    assertNull(service.captured.tenantId());
  }

  /** skipCount 必须原样透传到查询条件：它是「第 2 页起跳过 COUNT」的开关。 */
  @Test
  void skipCountFlagIsPassedThrough() {
    invoke(new Query().skipCount(Boolean.TRUE));

    assertEquals(true, service.captured.skipCount());
  }

  // ------------------------------------------------------------ 统一 from/to

  /** 日期形态的 from 取当天零点；to 取**次日零点**（仓储是左闭右开，等价「查到该天为止」）。 */
  @Test
  void unifiedFromToCloseDayBoundsForHalfOpenRepository() {
    invoke(new Query().from("2026-09-01").to("2026-09-30"));

    assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), service.captured.from());
    assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 0), service.captured.to());
    // 次日零点正好比「当天 23:59:59.999」晚 1 毫秒：闭区间语义下整天都在范围内
    assertTrue(service.captured.to().isAfter(LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)));
  }

  /** 同一端点的闭区间（from == to == 同一天）是合法区间：不会因为两端「相等」被误拒。 */
  @Test
  void unifiedSameDayRangeIsAccepted() {
    invoke(new Query().from("2026-09-30").to("2026-09-30"));

    assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0, 0, 0), service.captured.from());
    assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 0), service.captured.to());
  }

  /** 时刻形态按字面值使用；排他上界 = 该时刻 + 1 毫秒（DATETIME(3) 下才包含该毫秒本身）。 */
  @Test
  void unifiedFromToAcceptExplicitTime() {
    invoke(new Query().from("2026-09-01T08:30:00").to("2026-09-30T20:15:30"));

    assertEquals(LocalDateTime.of(2026, 9, 1, 8, 30, 0), service.captured.from());
    assertEquals(LocalDateTime.of(2026, 9, 30, 20, 15, 30, 1_000_000), service.captured.to());
  }

  /** 统一参数优先于任何既有别名：同时传时不得回退到旧参数。 */
  @Test
  void unifiedParamsTakePrecedenceOverLegacyAliases() {
    invoke(new Query()
        .from("2026-09-01").to("2026-09-30")
        .fromAt("2020-01-01 00:00:00").toAt("2020-01-02 00:00:00")
        .occurredFrom("2019-01-01").occurredTo("2019-01-02"));

    assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), service.captured.from());
    assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 0), service.captured.to());
  }

  /** 空串/空白等价于「没传」：既不筛，也不回退成非法值。 */
  @Test
  void blankUnifiedParamsMeanNoFilter() {
    invoke(new Query().from("").to("   "));

    assertNull(service.captured.from());
    assertNull(service.captured.to());
  }

  @Test
  void onlyOneBoundIsAllowedOnBothSides() {
    invoke(new Query().from("2026-09-01"));
    assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0, 0, 0), service.captured.from());
    assertNull(service.captured.to());

    invoke(new Query().to("2026-09-30"));
    assertNull(service.captured.from());
    assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 0), service.captured.to());
  }

  /** from > to：400 且错误码与全仓其它列表统一（不再是语义更弱的 INVALID_ARGUMENT）。 */
  @Test
  void invertedUnifiedRangeIsRejectedWithUnifiedErrorCode() {
    ApiException failure = assertThrows(ApiException.class,
        () -> invoke(new Query().from("2026-09-30").to("2026-09-01")));

    assertEquals(400, failure.getStatus());
    assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode());
    assertEquals(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID, failure.getMessage());
    assertNull(service.captured, "非法区间不得进入查询服务");
  }

  @Test
  void malformedUnifiedRangeIsRejectedWithUnifiedErrorCode() {
    for (String bad : new String[] {"2026/09/01", "20260901", "2026-13-01"}) {
      ApiException failure = assertThrows(ApiException.class, () -> invoke(new Query().from(bad)));
      assertEquals(TimeRangeParams.CODE_TIME_RANGE_INVALID, failure.getCode(), "非法值: " + bad);
    }
    assertNull(service.captured);
  }

  @Test
  void invalidPagingAndTimeAndEnumsAreRejectedWith400() {
    assertEquals(400, assertThrows(ApiException.class, () -> invoke(new Query().page("0"))).getStatus());
    assertEquals(400, assertThrows(ApiException.class, () -> invoke(new Query().page("abc"))).getStatus());
    assertEquals(400, assertThrows(ApiException.class, () -> invoke(new Query().pageSize("201"))).getStatus());
    assertEquals(400, assertThrows(ApiException.class, () -> invoke(new Query().fromAt("2026/09/01"))).getStatus());
    assertEquals(400, assertThrows(ApiException.class, () -> invoke(new Query().result("FAILED"))).getStatus());
  }

  @Test
  void actionCatalogReturnsItems() {
    Map<String, Object> response = controller.actions();
    assertEquals(2, response.get("total"));
    assertTrue(response.containsKey("items"));
  }

  // ------------------------------------------------------------ 调用辅助

  /** 把 23 个位置参数收进一个小 DSL，避免每个用例都写一长串 null（新加参数时只改这里）。 */
  private AuditPageView invoke(Query query) {
    return controller.list(query.page, query.pageSize, query.tenantId, query.organizationId, query.storeId,
        query.operatorId, query.operatorKeyword, query.action, query.actionPrefix, query.resourceType,
        query.resourceId, query.result, query.operatorType, query.requestId, query.traceId,
        query.from, query.to, query.fromAt, query.toAt, query.occurredFrom, query.occurredTo,
        query.order, query.skipCount);
  }

  private static final class Query {
    private String page;
    private String pageSize;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long operatorId;
    private String operatorKeyword;
    private String action;
    private String actionPrefix;
    private String resourceType;
    private String resourceId;
    private String result;
    private String operatorType;
    private String requestId;
    private String traceId;
    private String from;
    private String to;
    private String fromAt;
    private String toAt;
    private String occurredFrom;
    private String occurredTo;
    private String order;
    private Boolean skipCount;

    private Query page(String value) { this.page = value; return this; }
    private Query pageSize(String value) { this.pageSize = value; return this; }
    private Query tenantId(Long value) { this.tenantId = value; return this; }
    private Query organizationId(Long value) { this.organizationId = value; return this; }
    private Query storeId(Long value) { this.storeId = value; return this; }
    private Query operatorId(Long value) { this.operatorId = value; return this; }
    private Query operatorKeyword(String value) { this.operatorKeyword = value; return this; }
    private Query action(String value) { this.action = value; return this; }
    private Query actionPrefix(String value) { this.actionPrefix = value; return this; }
    private Query resourceType(String value) { this.resourceType = value; return this; }
    private Query resourceId(String value) { this.resourceId = value; return this; }
    private Query result(String value) { this.result = value; return this; }
    private Query operatorType(String value) { this.operatorType = value; return this; }
    private Query requestId(String value) { this.requestId = value; return this; }
    private Query traceId(String value) { this.traceId = value; return this; }
    private Query from(String value) { this.from = value; return this; }
    private Query to(String value) { this.to = value; return this; }
    private Query fromAt(String value) { this.fromAt = value; return this; }
    private Query toAt(String value) { this.toAt = value; return this; }
    private Query occurredFrom(String value) { this.occurredFrom = value; return this; }
    private Query occurredTo(String value) { this.occurredTo = value; return this; }
    private Query order(String value) { this.order = value; return this; }
    private Query skipCount(Boolean value) { this.skipCount = value; return this; }
  }

  /** 捕获查询入参的服务替身，避免测试依赖数据库。 */
  private static final class CapturingQueryService extends AuditQueryApplicationService {
    private AuditQueryRequest captured;

    private CapturingQueryService() {
      super(null, null);
    }

    @Override
    public AuditPageView list(AuditQueryRequest rawRequest) {
      this.captured = rawRequest.normalized();
      return new AuditPageView("PLATFORM", null, captured.page(), captured.pageSize(), 0L, 0, false, null, List.of());
    }

    @Override
    public List<Map<String, String>> actions() {
      return List.of(Map.of("code", "order.settle", "label", "结台结算"),
          Map.of("code", "product.create", "label", "商品新建"));
    }
  }
}
