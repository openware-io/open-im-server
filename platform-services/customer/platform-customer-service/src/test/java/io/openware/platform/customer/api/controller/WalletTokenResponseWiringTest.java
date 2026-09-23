package io.openware.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.api.controller.MemberController.MemberMeResponse;
import io.openware.platform.customer.application.MemberApplicationService;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 钱包展示字段的接线回归：C 端「余额 / 我的资产 / 流水」与后台「会员钱包 / 会员流水」响应
 * 必须走 {@link WalletTokenDisplayService} 补上 {@code tokenAmount} / {@code tokenBrandName}。
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
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    when(memberService.getOrCreateByAccount(ACCOUNT_ID)).thenReturn(member());
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** C 端余额（GET /me/wallet）：储值懒初始化，读路径只查账户不创建账户。 */
  @Test
  void meWalletResponseCarriesTokenFields() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 0L));

    CstWalletAccountPo wallet = myAssetsController.wallet();

    assertEquals("100", wallet.getTokenAmount());
    assertEquals("A380币", wallet.getTokenBrandName());
    assertEquals(100L, wallet.getAvailableAmount());
  }

  /** C 端流水（GET /me/wallet/ledger）。 */
  @Test
  void meWalletLedgerResponseCarriesTokenFieldsPerRow() {
    Page<CstWalletLedgerPo> page = new Page<>(1, 20, 1);
    page.setRecords(List.of(ledger(250L, 250L)));
    when(walletService.ledger(MEMBER_ID, 1L, 20L)).thenReturn(page);

    Page<CstWalletLedgerPo> result = myAssetsController.walletLedger(1L, 20L);

    assertSame(page, result);
    assertEquals("250", result.getRecords().get(0).getTokenAmount());
    assertEquals("A380币", result.getRecords().get(0).getTokenBrandName());
  }

  /** C 端「我的资产」（GET /business/members/me）内嵌的钱包对象同样带展示字段。 */
  @Test
  void memberMeResponseCarriesTokenFieldsOnWallet() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 500L));
    when(pointService.ensureAccount(MEMBER_ID)).thenReturn(pointAccount());

    MemberMeResponse me = memberController.me();

    assertEquals("100", me.wallet().getTokenAmount());
    assertEquals("A380币", me.wallet().getTokenBrandName());
    // 冻结 500 不影响可用代币展示口径，且冻结金额字段原样保留
    assertEquals(500L, me.wallet().getFrozenAmount());
    // 积分仍是数量口径：只有个数，没有币种/代币字段
    assertEquals(1200L, me.points().getAvailablePoints());
  }

  /** 后台会员钱包（GET /business/members/{id}/wallet）与会员流水。 */
  @Test
  void adminMemberWalletAndLedgerCarryTokenFields() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(300L, 0L));
    Page<CstWalletLedgerPo> page = new Page<>(1, 20, 1);
    page.setRecords(List.of(ledger(300L, 300L)));
    when(walletService.ledger(MEMBER_ID, 1L, 20L)).thenReturn(page);

    assertEquals("300", memberController.wallet(MEMBER_ID).getTokenAmount());
    assertEquals("300", memberController.walletLedger(MEMBER_ID, 1L, 20L)
        .getRecords().get(0).getTokenAmount());
  }

  /** 租户配置读取失败时，接线仍要给出缺省品牌与缺省比例换算的数量（不让响应缺字段）。 */
  @Test
  void wiringFallsBackToDefaultBrandWhenTenantConfigUnavailable() {
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);
    when(walletService.balance(MEMBER_ID)).thenReturn(account(100L, 0L));

    CstWalletAccountPo wallet = memberController.wallet(MEMBER_ID);

    assertEquals("A380币", wallet.getTokenBrandName());
    assertEquals("100", wallet.getTokenAmount());
  }

  /**
   * 懒初始化：没有账户时的零额只读视图（{@code id == null}）标记为「未开立」，
   * 界面才能区分「没开过储值账户」与「已开立、余额为 0」。
   */
  @Test
  void zeroWalletViewIsMarkedNotOpened() {
    CstWalletAccountPo zero = new CstWalletAccountPo();
    zero.setCustomerId(MEMBER_ID);
    zero.setCurrencyCode("USD");
    zero.setAvailableAmount(0L);
    zero.setFrozenAmount(0L);
    when(walletService.balance(MEMBER_ID)).thenReturn(zero);

    CstWalletAccountPo wallet = memberController.wallet(MEMBER_ID);

    assertFalse(wallet.getAccountOpened(), "没有账户行 = 未开立");
    assertEquals("0", wallet.getTokenAmount());
  }

  /** 已开立账户的响应标记 {@code accountOpened=true}。 */
  @Test
  void openedWalletViewIsMarkedOpened() {
    when(walletService.balance(MEMBER_ID)).thenReturn(account(300L, 0L));

    CstWalletAccountPo wallet = memberController.wallet(MEMBER_ID);

    assertTrue(wallet.getAccountOpened(), "有账户行 = 已开立");
  }

  /**
   * 储值管理列表（GET /business/members/wallets）：整页**一次**批量余额查询（不逐会员查），
   * 没有储值账户的会员按「余额 0 + 未开立」返回 —— 这正是此前逐个查询报错的场景。
   */
  @Test
  void walletsListUsesBatchBalanceQueryAndToleratesMissingAccounts() {
    CstMemberPo withoutAccount = new CstMemberPo();
    withoutAccount.setId(9L);
    withoutAccount.setMemberNo("M9");
    Page<CstMemberPo> page = new Page<>(1, 20, 2);
    page.setRecords(List.of(member(), withoutAccount));
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

  private static CstMemberPo member() {
    CstMemberPo po = new CstMemberPo();
    po.setId(MEMBER_ID);
    po.setAccountId(ACCOUNT_ID);
    po.setMemberNo("M1");
    return po;
  }

  private static CstWalletAccountPo account(Long available, Long frozen) {
    CstWalletAccountPo po = new CstWalletAccountPo();
    po.setId(1L);
    po.setCustomerId(MEMBER_ID);
    po.setCurrencyCode("USD");
    po.setAvailableAmount(available);
    po.setFrozenAmount(frozen);
    return po;
  }

  private static CstWalletLedgerPo ledger(long amount, long balanceAfter) {
    CstWalletLedgerPo po = new CstWalletLedgerPo();
    po.setId(1L);
    po.setAmount(amount);
    po.setBalanceAfter(balanceAfter);
    po.setCurrencyCode("USD");
    return po;
  }

  private static CstPointAccountPo pointAccount() {
    CstPointAccountPo po = new CstPointAccountPo();
    po.setId(1L);
    po.setCustomerId(MEMBER_ID);
    po.setAvailablePoints(1200L);
    po.setFrozenPoints(30L);
    return po;
  }
}
