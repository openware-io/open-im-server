package io.openware.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.api.controller.MemberController.MemberMeResponse;
import io.openware.platform.customer.api.dto.PointAccountView;
import io.openware.platform.customer.api.dto.PointLedgerRow;
import io.openware.platform.customer.api.dto.WalletAccountView;
import io.openware.platform.customer.api.dto.WalletLedgerPageView;
import io.openware.platform.customer.application.MemberApplicationService;
import io.openware.platform.customer.application.MemberDisplay;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 钱包展示字段的接线回归：C 端「余额 / 我的资产 / 流水」与后台「会员钱包 / 会员流水」响应
 * 必须走 {@link WalletTokenDisplayService} 补上 {@code tokenAmount} / {@code tokenBrandName}。
 *
 * <p>控制器现在返回 API 视图（{@link WalletAccountView} / {@link WalletLedgerPageView}），
 * 领域模型经 {@code WalletAccountView.from(...)} 转换后再补展示字段；本类断言的就是这条接线。
 */
class WalletTokenResponseWiringTest {

  private static final long TENANT_ID = 100L;
  private static final long ACCOUNT_ID = 1L;
  private static final long MEMBER_ID = 7L;

  private final MemberApplicationService memberService = mock(MemberApplicationService.class);
  private final PointApplicationService pointService = mock(PointApplicationService.class);
  private final WalletApplicationService walletService = mock(WalletApplicationService.class);
  private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);
  private final WalletTokenDisplayService displayService = new WalletTokenDisplayService(client);

  private MyAssetsController myAssetsController;
  private MemberController memberController;

  @BeforeEach
  void setUp() {
    myAssetsController = new MyAssetsController(memberService, pointService, walletService, displayService);
    memberController = new MemberController(memberService, pointService, walletService, displayService);
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, ACCOUNT_ID, 1));
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("SaaS 币", 100L));
    when(memberService.getOrCreateByAccount(ACCOUNT_ID))
        .thenReturn(MemberDisplay.withoutDisplay(member(MEMBER_ID)));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** C 端余额（GET /me/wallet）：储值懒初始化，读路径只查账户不创建账户。 */
  @Test
  void meWalletResponseCarriesTokenFields() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 0L));

    WalletAccountView wallet = myAssetsController.wallet();

    assertEquals("100", wallet.tokenAmount());
    assertEquals("SaaS 币", wallet.tokenBrandName());
    assertEquals(100L, wallet.availableAmount());
  }

  /** C 端流水（GET /me/wallet/ledger）。 */
  @Test
  void meWalletLedgerResponseCarriesTokenFieldsPerRow() {
    WalletAccountRepository.LedgerPage page = ledgerPage(ledger(250L, 250L));
    when(walletService.ledger(MEMBER_ID, 1L, 20L)).thenReturn(page);

    WalletLedgerPageView result = myAssetsController.walletLedger(1L, 20L);

    assertEquals(1, result.records().size());
    assertEquals(1L, result.total());
    assertEquals(1L, result.current());
    assertEquals("250", result.records().get(0).tokenAmount());
    assertEquals("SaaS 币", result.records().get(0).tokenBrandName());
  }

  /**
   * C 端积分（GET /me/points）：控制器把领域数据装配成响应视图，形状与迁移前一致。
   *
   * <p>账户此前直接返回持久化对象，{@code records} 此前是 MyBatis-Plus {@code Page} 的记录数组；
   * 这里断言装配后的键仍是旧键，且流水行只给「个数」（无币种、无代币字段）。
   */
  @Test
  void mePointsResponseKeepsLegacyFieldNames() {
    when(pointService.view(MEMBER_ID, 1L, 20L)).thenReturn(new PointApplicationService.MemberPointsData(
        pointAccount(), new PointAccountRepository.LedgerPage(List.of(pointLedger()), 1L), 1L, 20L));

    var view = myAssetsController.points(1L, 20L);

    assertEquals(1200L, view.account().availablePoints());
    assertEquals(30L, view.account().frozenPoints());
    assertEquals(1L, view.account().programId());
    assertEquals(1, view.records().size());
    assertEquals(100L, view.records().get(0).points());
    assertEquals(1200L, view.records().get(0).balanceAfter());
    assertEquals(1L, view.total());
    assertEquals(20L, view.size());
    assertEquals(1L, view.current());
    assertEquals(1L, view.pages());
  }

  /** C 端「我的资产」（GET /business/members/me）内嵌的钱包对象同样带展示字段。 */
  @Test
  void memberMeResponseCarriesTokenFieldsOnWallet() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 500L));
    when(pointService.ensureAccount(MEMBER_ID)).thenReturn(pointAccount());

    MemberMeResponse me = memberController.me();

    assertEquals("100", me.wallet().tokenAmount());
    assertEquals("SaaS 币", me.wallet().tokenBrandName());
    // 冻结 500 不影响可用代币展示口径，且冻结金额字段原样保留
    assertEquals(500L, me.wallet().frozenAmount());
    // 积分仍是数量口径：只有个数，没有币种/代币字段
    assertEquals(1200L, me.points().availablePoints());
    assertEquals(30L, me.points().frozenPoints());
  }

  /** 后台会员钱包（GET /business/members/{id}/wallet）与会员流水。 */
  @Test
  void adminMemberWalletAndLedgerCarryTokenFields() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(300L, 0L));
    when(walletService.ledger(MEMBER_ID, 1L, 20L)).thenReturn(ledgerPage(ledger(300L, 300L)));

    assertEquals("300", memberController.wallet(MEMBER_ID).tokenAmount());
    assertEquals("300", memberController.walletLedger(MEMBER_ID, 1L, 20L)
        .records().get(0).tokenAmount());
  }

  /** 租户配置读取失败时，接线仍要给出缺省品牌与缺省比例换算的数量（不让响应缺字段）。 */
  @Test
  void wiringFallsBackToDefaultBrandWhenTenantConfigUnavailable() {
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 0L));

    WalletAccountView wallet = memberController.wallet(MEMBER_ID);

    assertEquals("SaaS 币", wallet.tokenBrandName());
    assertEquals("100", wallet.tokenAmount());
  }

  /**
   * 懒初始化：没有账户时的零额只读视图（{@code id == null}）标记为「未开立」，
   * 界面才能区分「没开过储值账户」与「已开立、余额为 0」。
   */
  @Test
  void zeroWalletViewIsMarkedNotOpened() {
    when(walletService.balance(MEMBER_ID)).thenReturn(zeroAccount());

    WalletAccountView wallet = memberController.wallet(MEMBER_ID);

    assertFalse(wallet.accountOpened(), "没有账户行 = 未开立");
    assertEquals("0", wallet.tokenAmount());
  }

  /** 已开立账户的响应标记 {@code accountOpened=true}。 */
  @Test
  void openedWalletViewIsMarkedOpened() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(300L, 0L));

    WalletAccountView wallet = memberController.wallet(MEMBER_ID);

    assertTrue(wallet.accountOpened(), "有账户行 = 已开立");
  }

  /**
   * 储值管理列表（GET /business/members/wallets）：整页**一次**批量余额查询（不逐会员查），
   * 没有储值账户的会员按「余额 0 + 未开立」返回 —— 这正是此前逐个查询报错的场景。
   */
  @Test
  void walletsListUsesBatchBalanceQueryAndToleratesMissingAccounts() {
    MemberDisplay withoutAccount = MemberDisplay.withoutDisplay(member(9L));
    Page<MemberDisplay> page = new Page<>(1, 20, 2);
    page.setRecords(List.of(MemberDisplay.withoutDisplay(member(MEMBER_ID)), withoutAccount));
    when(memberService.list(1L, 20L, null, null, null, TimeRange.none())).thenReturn(page);
    when(walletService.balances(List.of(MEMBER_ID, 9L)))
        .thenReturn(Map.of(MEMBER_ID, account(300L, 0L)));

    Page<MemberController.MemberWalletRow> rows = memberController.wallets(1L, 20L, null, null, null);

    assertEquals(2, rows.getRecords().size());
    assertEquals(300L, rows.getRecords().get(0).availableAmount());
    assertEquals("300", rows.getRecords().get(0).tokenAmount());
    assertTrue(rows.getRecords().get(0).accountOpened());
    assertEquals(0L, rows.getRecords().get(1).availableAmount());
    assertEquals("0", rows.getRecords().get(1).tokenAmount());
    assertFalse(rows.getRecords().get(1).accountOpened());
    verify(walletService).balances(List.of(MEMBER_ID, 9L));
  }

  private static Member member(Long id) {
    return new Member(id, TENANT_ID, null, ACCOUNT_ID, null, null, null, "M" + id, null, null, null,
        null, Member.STATUS_ACTIVE, null, null, 0);
  }

  /** 已开户的领域账户 → 控制器入参。 */
  private static WalletAccount account(Long available, Long frozen) {
    return new WalletAccount(1L, TENANT_ID, MEMBER_ID, 1L, "USD", available, frozen,
        null, null, true, WalletAccount.STATUS_ACTIVE, 0);
  }

  /** 零额只读视图：没有账户行，{@code id == null}。 */
  private static WalletAccount zeroAccount() {
    return WalletAccount.zeroFor(MEMBER_ID, 1L, "USD");
  }

  private static WalletAccountRepository.LedgerPage ledgerPage(WalletLedgerEntry... rows) {
    return new WalletAccountRepository.LedgerPage(List.of(rows), rows.length);
  }

  private static WalletLedgerEntry ledger(long amount, long balanceAfter) {
    return new WalletLedgerEntry(1L, TENANT_ID, null, 1L, "RECHARGE", amount, balanceAfter, "USD",
        null, null, null, null, null, LocalDateTime.now());
  }

  private static PointAccount pointAccount() {
    return new PointAccount(1L, TENANT_ID, MEMBER_ID, 1L, 1200L, 30L, 0);
  }

  private static PointLedgerEntry pointLedger() {
    return new PointLedgerEntry(5L, TENANT_ID, null, 1L, "ADJUST", 100L, "USD", 1200L, null, null,
        "cmd-1", null, null, LocalDateTime.now(), LocalDateTime.now());
  }
}
