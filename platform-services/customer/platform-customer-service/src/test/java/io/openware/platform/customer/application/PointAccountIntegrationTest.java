package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository.LedgerPage;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 积分核心写路径集成测试（<b>真跑</b>）：H2(MODE=MySQL) + 真实 MyBatis + 真实仓储适配器，
 * 只有审计客户端被 mock。
 *
 * <p><b>为什么这个类必须存在</b>：单元测试 mock 端口，编译期又查不出列名，
 * 所以"编译通过 + 单测全绿"不等于这条 SQL 能在真库上跑。钱包切片就是在这里炸的——
 * CAS 适配器往 UPDATE 里写了 {@code @TableField(exist = false)} 的展示字段，
 * MyBatis-Plus 抛 {@code can not find lambda cache for this property}，四个写路径全部运行时失败，
 * 而 {@code test-compile} 完全看不出来（见 {@code CUSTOMER_LAYERING_HANDOFF.md} §5.2）。
 * 本类用真适配器打真表，把那类缺陷挡在合并之前。
 *
 * <p>读取一律走领域端口（{@link PointAccountRepository}），断言的是"落库后的领域事实"
 * （余额、版本、流水条数、CAS 是否真的按版本判定）；SQL 形状由
 * {@code PointAccountRepositoryAdapterTest} 覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointAccountIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2001L;

  @MockitoBean
  private AuditClient auditClient;

  @Autowired
  private PointApplicationService pointApplicationService;

  @Autowired
  private PointAccountRepository pointAccountRepository;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, STORE_ID, null, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  /** 懒创建 + 首次调整：账户行落库（零额起点），账本记一笔，返回值与库里一致。 */
  @Test
  void adjustCreatesAccountAndAppendsLedgerAgainstRealDatabase() {
    long customerId = 7101L;

    PointAccount saved = pointApplicationService.adjust(STORE_ID, customerId, 500L, "首单补偿", "lg-1");

    assertNotNull(saved.id(), "账户必须已落库");
    assertEquals(500L, saved.available());
    assertEquals(1, saved.currentVersion(), "一次 CAS 后版本从 0 到 1");
    // tenantId 刻意不在此断言：落库的 tenant_id 由 MyBatis 租户拦截器在 SQL 层注入
    // （PlatformTenantLineHandler），实体上的字段不参与——迁移前后都是这个行为。

    PointAccount stored = pointAccountRepository.findByCustomer(customerId).orElseThrow();
    assertEquals(saved.id(), stored.id());
    assertEquals(500L, stored.available());
    assertEquals(1, stored.currentVersion(), "返回的版本必须与库中一致");

    LedgerPage page = pointAccountRepository.listLedger(saved.id(), null, 0L, 20L);
    assertEquals(1L, page.total());
    PointLedgerEntry row = page.entries().get(0);
    assertEquals("ADJUST", row.entryType());
    assertEquals(500L, row.pointsOrZero());
    assertEquals(500L, row.balanceAfterOrZero());
    assertEquals(STORE_ID, row.storeId());
    assertEquals("lg-1", row.idempotencyKey());
    // 币种快照必须真的落库（非空），否则报表按币种归集时会丢行。
    assertTrue(row.currencyCode() != null && !row.currencyCode().isBlank(), "币种快照必须落库");
    assertNotNull(row.occurredAt());
    assertNotNull(row.createdAt(), "created_at 是 NOT NULL 列，必须真的写进去");
  }

  /** 连续两次调整：余额累加、版本逐次自增、账本按 id 倒序且历史行不被改写。 */
  @Test
  void repeatedAdjustmentsAccumulateBalanceAndIncrementVersion() {
    long customerId = 7102L;

    PointAccount first = pointApplicationService.adjust(STORE_ID, customerId, 100L, "a", "lg-2a");
    PointAccount second = pointApplicationService.adjust(STORE_ID, customerId, 250L, "b", "lg-2b");

    assertEquals(first.id(), second.id(), "同一会员复用同一账户行");
    assertEquals(350L, second.available());
    assertEquals(1, first.currentVersion());
    assertEquals(2, second.currentVersion(), "每次 CAS 自增 1");

    // 账本按 id 倒序：最近一笔在最前，历史行的 balance_after 保持当时的值（只追加）。
    List<PointLedgerEntry> rows = pointAccountRepository.listLedger(first.id(), null, 0L, 20L).entries();
    assertEquals(2, rows.size());
    assertEquals(250L, rows.get(0).pointsOrZero());
    assertEquals(350L, rows.get(0).balanceAfterOrZero());
    assertEquals(100L, rows.get(1).pointsOrZero());
    assertEquals(100L, rows.get(1).balanceAfterOrZero());
  }

  /**
   * CAS 真的按版本判定：把期望版本加大 1（等于"我拿的是别人已经写过的版本"）必须 0 行受影响。
   *
   * <p>这条断言是"版本不是装饰"的<b>运行时</b>证据——只要有人把 adapter 里的
   * {@code .eq(version, expectedVersion)} 删掉，这里就会从 false 变成 true。
   */
  @Test
  void staleVersionCasAffectsNoRow() {
    long customerId = 7103L;
    PointAccount account = pointApplicationService.ensureAccount(customerId);

    boolean updated = pointAccountRepository.updateBalanceWithVersion(
        new PointAccount(account.id(), account.tenantId(), account.customerId(), account.programId(),
            999L, 0L, account.currentVersion()), account.currentVersion() + 1);

    assertEquals(false, updated, "版本不匹配时 CAS 必须 0 行受影响");
    assertEquals(0L, pointAccountRepository.findByCustomer(customerId).orElseThrow().available(),
        "CAS 失败不得改动余额");
  }

  /** 抵扣与释放（内部组合收款路径）：余额先减后加，两条流水方向相反。 */
  @Test
  void redeemThenReleaseRoundTripsThroughTheLedger() {
    long customerId = 7104L;
    pointApplicationService.adjust(STORE_ID, customerId, 1000L, "充值", "lg-4a");

    PointAccount redeemed = pointApplicationService.redeem(STORE_ID, customerId, 300L, 88L, "lg-4b");
    assertEquals(700L, redeemed.available());

    PointAccount released = pointApplicationService.release(STORE_ID, customerId, 300L, 88L, "lg-4c");
    assertEquals(1000L, released.available());

    List<PointLedgerEntry> rows =
        pointAccountRepository.listLedger(redeemed.id(), null, 0L, 20L).entries();
    assertEquals(3, rows.size());
    assertEquals("REVERSE", rows.get(0).entryType());
    assertEquals(300L, rows.get(0).pointsOrZero());
    assertEquals("REDEEM", rows.get(1).entryType());
    assertEquals(-300L, rows.get(1).pointsOrZero());
  }

  /** 获得积分：倍率按 DOWN 取整落库，规则快照与幂等键原样保存。 */
  @Test
  void earnWritesRuleSnapshotAndRoundedPoints() {
    long customerId = 7105L;

    PointAccount saved = pointApplicationService.earn(STORE_ID, customerId, 199L, 99L, "lg-5");

    assertEquals(199L, saved.available(), "缺省倍率 1：199 × 1 向下取整 = 199");
    PointLedgerEntry row =
        pointAccountRepository.listLedger(saved.id(), null, 0L, 20L).entries().get(0);
    assertEquals("EARN", row.entryType());
    assertEquals(199L, row.pointsOrZero());
    assertNotNull(row.ruleSnapshotJson(), "EARN 必须固化规则快照");
    assertTrue(row.ruleSnapshotJson().contains("earnRate"), "快照里必须带倍率");
  }

  /**
   * 幂等：同一幂等键再来一次 → 409，且<b>账本与余额都不动</b>。
   *
   * <p>唯一键 {@code uk_cst_point_ledger_idem} 是并发同键的最后一道闸，这里验的是预检这条路。
   */
  @Test
  void duplicateIdempotencyKeyIsRejectedWithoutSecondWrite() {
    long customerId = 7106L;
    pointApplicationService.adjust(STORE_ID, customerId, 100L, "a", "lg-6");

    ApiException failure = assertThrows(ApiException.class,
        () -> pointApplicationService.adjust(STORE_ID, customerId, 100L, "a", "lg-6"));

    assertEquals(409, failure.getStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
    PointAccount stored = pointAccountRepository.findByCustomer(customerId).orElseThrow();
    assertEquals(100L, stored.available(), "重复幂等键不得再加一次积分");
    assertEquals(1L, pointAccountRepository.countLedger(stored.id(), null));
  }

  /** 余额不足：422 LEDGER_INSUFFICIENT，且余额与账本都不动。 */
  @Test
  void redeemBeyondBalanceIsRejectedWithoutSideEffects() {
    long customerId = 7107L;
    pointApplicationService.adjust(STORE_ID, customerId, 50L, "a", "lg-7a");

    ApiException failure = assertThrows(ApiException.class,
        () -> pointApplicationService.redeem(STORE_ID, customerId, 500L, 77L, "lg-7b"));

    assertEquals(422, failure.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", failure.getCode());
    PointAccount stored = pointAccountRepository.findByCustomer(customerId).orElseThrow();
    assertEquals(50L, stored.available());
    assertEquals(1L, pointAccountRepository.countLedger(stored.id(), null));
  }

  /** 门店读范围：指定门店能查到本店流水，指定另一个门店查不到（总部不传门店看全量）。 */
  @Test
  void ledgerStoreFilterIsApplied() {
    long customerId = 7108L;
    PointAccount saved = pointApplicationService.adjust(STORE_ID, customerId, 100L, "a", "lg-8");

    assertEquals(1L, pointAccountRepository.countLedger(saved.id(), STORE_ID));
    assertEquals(0L, pointAccountRepository.countLedger(saved.id(), 9999L));
    assertEquals(1L, pointAccountRepository.countLedger(saved.id(), null));
  }
}
