package io.openware.common.audit.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.audit.api.dto.AuditLogView;
import io.openware.common.audit.api.dto.AuditPageView;
import io.openware.common.audit.api.dto.AuditQueryRequest;
import io.openware.common.audit.application.AuditScopeResolver;
import io.openware.common.audit.application.retention.AuditRetentionPolicy;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.audit.domain.model.OperatorDisplay;
import io.openware.common.audit.domain.repository.AuditArchiveManifestRepository;
import io.openware.common.audit.domain.repository.AuditLogRepository;
import io.openware.common.audit.infra.security.AuditCallerScope;
import io.openware.common.audit.infra.security.AuditCallerScopeHolder;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 审计查询权限分层回归（服务端强制，前端参数不能越权）：
 * 平台视角看全部租户；租户视角强制收敛；跨租户 tenantId 403；缺 audit.view 一律 403；详情越权 404。
 */
class AuditQueryApplicationServiceTest {

  private static final long TENANT_A = 1001L;
  private static final long TENANT_B = 2002L;

  private final InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
  private final AuditQueryApplicationService service =
      new AuditQueryApplicationService(repository, new AuditScopeResolver());

  @AfterEach
  void cleanup() {
    TenantContextHolder.clear();
    AuditCallerScopeHolder.clear();
  }

  @Test
  void platformScopeSeesAllTenantsAndCanFilterByTenantId() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");

    AuditPageView all = service.list(request(null, null));
    assertEquals("PLATFORM", all.scope());
    assertEquals(3, all.total());
    assertEquals(3, all.items().size());
    assertEquals("A380 租户", all.items().get(0).tenantName());

    AuditPageView filtered = service.list(request(TENANT_B, null));
    assertEquals(1, filtered.total());
    assertEquals(TENANT_B, filtered.items().get(0).tenantId());
    assertEquals("B 租户", filtered.items().get(0).tenantName());
  }

  /** 平台账号即便选择了某个租户上下文，平台视角仍然可跨租户（scopeType=PLATFORM）。 */
  @Test
  void platformAccountInsideTenantContextStillSeesEveryTenant() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    assertEquals(3, service.list(request(null, null)).total());
  }

  /** 无租户约束的平台上下文（tenantId<=0）同样按平台视角处理。 */
  @Test
  void contextWithoutTenantConstraintIsPlatformScope() {
    TenantContextHolder.set(new TenantContext(0L, null, null, 9L, 1, List.of("audit.view")));
    AuditCallerScopeHolder.set(new AuditCallerScope(null, 0L));

    assertEquals(3, service.list(request(null, null)).total());
  }

  @Test
  void tenantScopeIsForcedToOwnTenantEvenWhenClientAsksForAll() {
    signIn(77L, TENANT_A, List.of("audit.view"), "TENANT");

    AuditPageView page = service.list(request(null, null));

    assertEquals("TENANT", page.scope());
    assertEquals(TENANT_A, page.effectiveTenantId());
    assertEquals(2, page.total());
    assertTrue(page.items().stream().allMatch(item -> TENANT_A == item.tenantId()));
  }

  @Test
  void tenantScopeWithSameTenantParameterIsAccepted() {
    signIn(77L, TENANT_A, List.of("audit.view"), "TENANT");
    assertEquals(2, service.list(request(TENANT_A, null)).total());
  }

  /** 越权尝试：租户传别的 tenantId 直接 403（不静默降级，越权要可见）。 */
  @Test
  void tenantScopeWithForeignTenantIdIsForbidden() {
    signIn(77L, TENANT_A, List.of("audit.view"), "TENANT");

    ApiException error = assertThrows(ApiException.class, () -> service.list(request(TENANT_B, null)));

    assertEquals(403, error.getStatus());
    assertEquals("AUDIT_TENANT_FORBIDDEN", error.getCode());
  }

  @Test
  void missingPermissionIsAlwaysForbidden() {
    signIn(77L, TENANT_A, List.of("order.view"), "TENANT");

    ApiException error = assertThrows(ApiException.class, () -> service.list(request(null, null)));
    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());

    // 平台账号缺 audit.view 同样 403。
    signIn(9L, TENANT_A, List.of("order.view"), "PLATFORM");
    assertEquals(403, assertThrows(ApiException.class, () -> service.list(request(null, null))).getStatus());
  }

  /** 没有签名上下文（无法验证权限）一律 403：不能凭「没有租户头」就放行全量审计。 */
  @Test
  void requestWithoutContextIsForbidden() {
    ApiException error = assertThrows(ApiException.class, () -> service.list(request(null, null)));
    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
  }

  @Test
  void detailIsScopedAndForeignRecordIsNotFound() {
    signIn(77L, TENANT_A, List.of("audit.view"), "TENANT");
    long ownRecordId = repository.records.get(0).getId();
    long foreignRecordId = repository.records.get(1).getId();
    assertEquals(TENANT_A, repository.records.get(0).getTenantId());
    assertEquals(TENANT_B, repository.records.get(1).getTenantId());

    AuditLogView view = service.detail(ownRecordId);
    assertEquals(TENANT_A, view.tenantId());
    assertEquals("order.settle", view.action());
    assertEquals("结台结算", view.actionLabel());

    ApiException error = assertThrows(ApiException.class, () -> service.detail(foreignRecordId));
    assertEquals(404, error.getStatus());
    assertEquals("AUDIT_NOT_FOUND", error.getCode());
  }

  @Test
  void platformCanReadAnyTenantDetail() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    assertEquals(TENANT_B, service.detail(repository.records.get(1).getId()).tenantId());
  }

  @Test
  void actionCatalogRequiresAuditPermission() {
    signIn(77L, TENANT_A, List.of("audit.view"), "TENANT");
    assertTrue(service.actions().stream().anyMatch(item -> "order.settle".equals(item.get("code"))));

    TenantContextHolder.clear();
    assertThrows(ApiException.class, () -> service.actions());
  }

  @Test
  void unknownResultFilterIsRejectedWith400() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    AuditQueryRequest invalid = new AuditQueryRequest(1, 20, null, null, null, null, null, null, null, null, null,
        "FAILED", null, null, null, null, null, false, false);

    ApiException error = assertThrows(ApiException.class, () -> service.list(invalid));
    assertEquals(400, error.getStatus());
    assertEquals("INVALID_ARGUMENT", error.getCode());
  }

  /**
   * count 收敛：审计表是高频写入大表，列表接口的主要成本就是 COUNT。
   * 第 2 页起前端回传 skipCount → 服务端完全跳过统计，回 total=-1（前端沿用上一页总数）。
   */
  @Test
  void skipCountSkipsTotalAndKeepsItems() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    AuditQueryRequest request = new AuditQueryRequest(2, 20, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, false, true);

    AuditPageView page = service.list(request);

    assertEquals(AuditPageView.TOTAL_SKIPPED, page.total());
    assertEquals(0, page.totalPages());
    assertEquals(false, page.totalCapped());
    assertEquals(3, page.items().size());
  }

  /**
   * 统计上界：匹配行数到达 countCap 时只回「已封顶」，不再继续聚合；
   * 前端据 totalCapped 显示「N+」，不再假装是精确总数。
   */
  @Test
  void countStopsAtCapAndReportsCapped() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    AuditQueryApplicationService capped = new AuditQueryApplicationService(
        repository, new AuditScopeResolver(), 2L);

    AuditPageView page = capped.list(request(null, null));

    assertEquals(2, page.total());
    assertEquals(true, page.totalCapped());
    assertEquals(1, page.totalPages());
  }

  /**
   * 保留策略的可查下限：已归档月份必须被移出列表范围（左边界收敛到下限），并在响应里显式回执，
   * 避免运营把「查不到」误解成「没有记录」。
   */
  @Test
  void archivedMonthsAreExcludedByRetentionFloor() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    AuditArchiveManifestRepository manifest = org.mockito.Mockito.mock(AuditArchiveManifestRepository.class);
    org.mockito.Mockito.when(manifest.archivedMonths()).thenReturn(java.util.Set.of(java.time.YearMonth.of(2024, 9)));
    AuditQueryApplicationService limited = new AuditQueryApplicationService(repository, new AuditScopeResolver(),
        100_000L, new AuditRetentionPolicy(24, 2, ""), manifest);
    AuditQueryRequest early = new AuditQueryRequest(1, 20, null, null, null, null, null, null, null, null, null,
        null, null, null, null, java.time.LocalDateTime.of(2020, 1, 1, 0, 0), null, false, false);

    AuditPageView page = limited.list(early);

    assertEquals("2024-10-01T00:00", page.retentionFloor(), "回执可查下限（最新归档月份的次月 1 号）");
    assertEquals(java.time.LocalDateTime.of(2024, 10, 1, 0, 0), repository.lastQuery.from(),
        "早于下限的左边界必须收敛到下限，不去扫已归档月份");
  }

  /** 请求区间本身晚于下限时不得被改动（下限只做下界收敛，不扩大也不缩小查询范围）。 */
  @Test
  void retentionFloorDoesNotWidenRequestedRange() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    AuditArchiveManifestRepository manifest = org.mockito.Mockito.mock(AuditArchiveManifestRepository.class);
    org.mockito.Mockito.when(manifest.archivedMonths()).thenReturn(java.util.Set.of(java.time.YearMonth.of(2024, 9)));
    AuditQueryApplicationService limited = new AuditQueryApplicationService(repository, new AuditScopeResolver(),
        100_000L, new AuditRetentionPolicy(24, 2, ""), manifest);
    AuditQueryRequest later = new AuditQueryRequest(1, 20, null, null, null, null, null, null, null, null, null,
        null, null, null, null, java.time.LocalDateTime.of(2026, 1, 1, 0, 0), null, false, false);

    limited.list(later);

    assertEquals(java.time.LocalDateTime.of(2026, 1, 1, 0, 0), repository.lastQuery.from());
  }

  /** 没有任何归档时不设下限（新库刚上线、还没到 24 个月时的正常状态）。 */
  @Test
  void noArchiveMeansNoRetentionFloor() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");

    AuditPageView page = service.list(request(null, null));

    assertNull(page.retentionFloor());
    assertNull(repository.lastQuery.from());
  }

  private AuditQueryRequest request(Long tenantId, String operatorKeyword) {
    return new AuditQueryRequest(1, 20, tenantId, null, null, null, operatorKeyword, null, null, null, null, null,
        null, null, null, null, null, false, false);
  }

  /**
   * 内部通道查询（IM 后台「审计日志」页）：走 HMAC 内部鉴权，**没有** SaaS 运营上下文，
   * 因此不判 {@code audit.view}，按平台视角返回全租户数据。
   *
   * <p>这里刻意不清租户上下文：公开端点 {@link AuditQueryApplicationService#list} 在同一状态下会
   * 403，内部端点必须能正常返回——两者差异只有视角来源。
   */
  @Test
  void internalSearchUsesPlatformScopeWithoutPermissionGuard() {
    cleanup();

    AuditPageView page = service.searchInternal(request(null, null));

    assertEquals("PLATFORM", page.scope());
    assertEquals(3, page.total());
    assertEquals(3, page.items().size());
  }

  /** 内部通道同样必须走规范化校验（非法 result 一律 400，不静默忽略）。 */
  @Test
  void internalSearchRejectsInvalidResultFilter() {
    cleanup();
    AuditQueryRequest invalid = new AuditQueryRequest(1, 20, null, null, null, null, null, null, null, null, null,
        "NOT_A_RESULT", null, null, null, null, null, false, false);

    ApiException exception = assertThrows(ApiException.class, () -> service.searchInternal(invalid));

    assertEquals(400, exception.getStatus());
  }

  /**
   * 操作人姓名展示：审计表只冗余 {@code operator_id}，姓名/账号为空时按它到账号表补全。
   *
   * <p>两条口径一起锁住：缺失才补（历史记录里没有姓名时运营要看到「谁做的」，
   * 而不是一行 {@code #1}）；记录里已存的值优先（那是动作发生当时的快照，账号改名不追改历史）。
   */
  @Test
  void operatorDisplayIsFilledFromAccountTableOnlyWhenMissing() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    // 第 1 条模拟历史记录：上报方没带姓名/账号；第 2 条保留已存值。
    repository.records.get(0).setOperatorName(null);
    repository.records.get(0).setOperatorAccount(null);
    repository.operatorDisplays.put(77L, new OperatorDisplay("李四", "lisi"));

    AuditPageView page = service.list(request(null, null));

    assertEquals("李四", page.items().get(0).operatorName(), "缺姓名时按 operator_id 补全");
    assertEquals("lisi", page.items().get(0).operatorAccount(), "登录名同样补全");
    assertEquals("张三", page.items().get(1).operatorName(), "已存姓名优先，不被账号表当前值覆盖");
    assertEquals("zhangsan", page.items().get(1).operatorAccount());
  }

  /** 详情与列表同一套补全口径：抽屉里的操作人不能又变回 #ID。 */
  @Test
  void operatorDisplayIsFilledOnDetailToo() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    repository.records.get(0).setOperatorName(null);
    repository.records.get(0).setOperatorAccount(null);
    repository.operatorDisplays.put(77L, new OperatorDisplay("李四", "lisi"));

    AuditLogView detail = service.detail(1L);

    assertEquals("李四", detail.operatorName());
    assertEquals("lisi", detail.operatorAccount());
  }

  /** 账号表不可用（跨域表缺授权/宕机）时列表照常返回，只是没有姓名——不能把整页打死。 */
  @Test
  void listStillWorksWhenOperatorDisplayResolutionIsUnavailable() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    repository.records.get(0).setOperatorName(null);

    AuditPageView page = service.list(request(null, null));

    assertEquals(3, page.items().size());
    assertNull(page.items().get(0).operatorName());
  }

  // ------------------------------------------------------------ detailJson 投影形状

  /**
   * 线上缺陷回归：详情投影必须是**业务 JSON 文本**，不能是 Jackson 的节点对象。
   *
   * <p>Spring Boot 4 的 HTTP 消息转换器是 Jackson 3，Jackson 2 的 {@code JsonNode} 会被当普通 POJO
   * 序列化成 {@code {"array":false,...,"nodeType":"OBJECT",...}}，业务内容丢失。因此这里锁死
   * {@code detailJson()} 的类型是 {@code String}、内容是可直接 {@code JSON.parse} 的业务对象文本。
   */
  @Test
  void detailJsonIsRawBusinessJsonText() throws Exception {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    repository.records.get(0).setDetailJson("{\"orderId\":76,\"status\":\"ACTIVE\"}");

    AuditLogView view = service.detail(1L);

    assertEquals("{\"orderId\":76,\"status\":\"ACTIVE\"}", view.detailJson());
    assertEquals(76, new ObjectMapper().readTree(view.detailJson()).path("orderId").asInt(),
        "前端 JSON.parse 后必须能拿到业务字段");
  }

  /** detail 缺失/空白 → null（前端显示「-」），不是 500，也不是空对象。 */
  @Test
  void missingDetailProjectsAsNull() {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    repository.records.get(0).setDetailJson(null);
    repository.records.get(1).setDetailJson("   ");

    assertNull(service.detail(1L).detailJson());
    assertNull(service.detail(2L).detailJson());
  }

  /**
   * 历史脏数据（不是合法 JSON 的纯文本）：包成 JSON 字符串字面量，保证返回体里这一段仍是合法 JSON，
   * 原文不丢——不能让一条脏数据把整页查询打成 500。
   */
  @Test
  void nonJsonDetailFallsBackToQuotedJsonString() throws Exception {
    signIn(9L, TENANT_A, List.of("audit.view"), "PLATFORM");
    repository.records.get(0).setDetailJson("legacy-text-not-json");

    assertEquals("\"legacy-text-not-json\"", service.detail(1L).detailJson());
    assertEquals("legacy-text-not-json", new ObjectMapper().readTree(service.detail(1L).detailJson()).asText(),
        "降级后 JSON.parse 得到原字符串，原文不丢");
  }

  private void signIn(long accountId, long tenantId, List<String> permissions, String scopeType) {
    TenantContextHolder.set(new TenantContext(tenantId, null, null, accountId, 1, permissions));
    AuditCallerScopeHolder.set(new AuditCallerScope(scopeType, tenantId));
  }

  /** 内存仓储：3 条记录（租户 A 两条、租户 B 一条），只实现查询用到的过滤语义。 */
  private static final class InMemoryAuditLogRepository implements AuditLogRepository {
    private final List<AuditLog> records = new ArrayList<>();
    /** 最近一次查询条件：用于断言保留策略对左边界（from）的收敛。 */
    private Query lastQuery;

    private InMemoryAuditLogRepository() {
      records.add(record(1L, TENANT_A, "order.settle", "结台结算"));
      records.add(record(2L, TENANT_B, "payment.collect", "组合收款"));
      records.add(record(3L, TENANT_A, "order.item.add", "订单加项"));
    }

    private static AuditLog record(long id, long tenantId, String action, String label) {
      AuditLog log = new AuditLog();
      log.setId(id);
      log.setTenantId(tenantId);
      log.setOperatorId(77L);
      log.setOperatorName("张三");
      log.setOperatorAccount("zhangsan");
      log.setOperatorType("TENANT");
      log.setAction(action);
      log.setActionLabel(label);
      log.setResourceType("ord_order");
      log.setResourceId(String.valueOf(id));
      log.setResult("SUCCESS");
      log.setCreatedAt(LocalDateTime.now());
      return log;
    }

    @Override
    public SaveResult saveIfAbsent(AuditLog log) {
      throw new UnsupportedOperationException("查询测试不写入");
    }

    @Override
    public Optional<AuditLog> findById(long id) {
      return records.stream().filter(item -> item.getId() == id).findFirst();
    }

    @Override
    public List<AuditLog> findPage(Query query) {
      lastQuery = query;
      return records.stream().filter(item -> matches(query, item)).toList();
    }

    @Override
    public long count(Query query) {
      lastQuery = query;
      return records.stream().filter(item -> matches(query, item)).count();
    }

    @Override
    public Map<Long, String> tenantNames(Collection<Long> tenantIds) {
      return Map.of(TENANT_A, "A380 租户", TENANT_B, "B 租户");
    }

    /** 账号表里能查到的操作人展示信息（缺省为空，表示跨域表不可用或没这个账号）。 */
    private final Map<Long, OperatorDisplay> operatorDisplays = new HashMap<>();

    @Override
    public Map<Long, OperatorDisplay> operatorDisplays(Collection<Long> operatorIds) {
      Map<Long, OperatorDisplay> resolved = new HashMap<>();
      for (Long id : operatorIds) {
        OperatorDisplay display = operatorDisplays.get(id);
        if (display != null) {
          resolved.put(id, display);
        }
      }
      return resolved;
    }

    private static boolean matches(Query query, AuditLog item) {
      return query.tenantId() == null || query.tenantId().equals(item.getTenantId());
    }
  }
}
