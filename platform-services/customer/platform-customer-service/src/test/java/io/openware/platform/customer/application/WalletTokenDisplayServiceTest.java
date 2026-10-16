package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.api.dto.WalletAccountView;
import io.openware.platform.customer.api.dto.WalletLedgerPageView;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 钱包「代币数量」展示口径：{@code 代币数量 = 余额最小单位 ÷ 100 × ratio}（四舍五入取整，ratio 缺省 100）。
 *
 * <p>断言三件事：①换算值正确（含 0 余额、冻结不影响可用口径、ratio 非法回退缺省、四舍五入）；
 * ②代币字段是<b>纯数量字符串</b>，不携带币种/货币符号；③代币数量绝不参与任何金额字段与金额合计。
 *
 * <p><b>输入/输出都是不可变视图</b>（{@code WalletAccountView} / {@link LedgerPage}）：
 * {@code decorate} 返回<b>新</b>对象，不再"原地补字段"——领域条目与响应视图都是 record，改不了也不该改。
 */
class WalletTokenDisplayServiceTest {

  private static final long TENANT_ID = 100L;

  private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);
  private final WalletTokenDisplayService service = new WalletTokenDisplayService(client);

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 100 最小单位（= 1 主单位）× ratio 100 → 100 个代币；品牌名取租户配置。 */
  @Test
  void convertsMinorAmountWithTenantRatioAndBrandName() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("皇冠币", 100L));

    WalletAccountView account = service.decorate(account(100L, 0L));

    assertEquals("100", account.tokenAmount());
    assertEquals("皇冠币", account.tokenBrandName());
  }

  /** ratio 缺省（缺配置/调用失败都退化成 100）：250 最小单位 → 250 个代币。 */
  @Test
  void usesDefaultRatioWhenTenantConfigFallsBack() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);

    WalletAccountView account = service.decorate(account(250L, 0L));

    assertEquals("250", account.tokenAmount());
    assertEquals("A380币", account.tokenBrandName());
  }

  /** 余额 0 → 0 个代币（不产生空值/异常）。 */
  @Test
  void zeroBalanceYieldsZeroTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));

    assertEquals("0", service.decorate(account(0L, 0L)).tokenAmount());
  }

  /** 冻结金额不影响可用代币展示口径：可用 100 → 100 个代币（冻结金额字段原样保留）。 */
  @Test
  void frozenAmountDoesNotAffectAvailableTokenDisplay() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));

    WalletAccountView account = service.decorate(account(100L, 999999L));

    assertEquals("100", account.tokenAmount());
    assertEquals(999999L, account.frozenAmount());
  }

  /** ratio 非法（非正）由本服务兜底回退缺省 100，不让换算结果变成 0 或负数。 */
  @Test
  void invalidRatioFallsBackToDefault() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 0L));
    assertEquals("100", service.decorate(account(100L, 0L)).tokenAmount());

    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", -5L));
    assertEquals("100", service.decorate(account(100L, 0L)).tokenAmount());
  }

  /** 四舍五入取整：150 最小单位 × ratio 33 = 49.5 → 50。 */
  @Test
  void roundsHalfUpToWholeTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 33L));

    assertEquals("50", service.decorate(account(150L, 0L)).tokenAmount());
    assertEquals("0", service.decorate(account(1L, 0L)).tokenAmount());
  }

  /** 金额为 null（异常数据）按 0 处理，不抛异常。 */
  @Test
  void nullAmountYieldsZeroTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);

    assertEquals("0", service.decorate(account(null, 0L)).tokenAmount());
  }

  /** 无租户上下文：不查询租户配置（tenantId=null），品牌/比例回退缺省。 */
  @Test
  void missingTenantContextFallsBackToDefaults() {
    TenantContextHolder.clear();
    when(client.resolve(null)).thenReturn(WalletTokenConfig.DEFAULT);

    WalletAccountView account = service.decorate(account(100L, 0L));

    verify(client).resolve(null);
    assertEquals("A380币", account.tokenBrandName());
    assertEquals("100", account.tokenAmount());
  }

  /** 入参 null（无账户/已由上层判空）：原样返回 null，不 NPE。 */
  @Test
  void nullAccountStaysNull() {
    assertNull(service.decorate((WalletAccountView) null));
  }

  /** 流水行按本笔发生额换算代币数量，分页元信息与金额字段原样保留。 */
  @Test
  void decoratesLedgerPageByEntryAmount() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    LedgerPage page = new LedgerPage(List.of(ledger(100L, 5000L), ledger(250L, 4750L)), 2L);

    WalletLedgerPageView decorated = service.decorate(page, 1L, 20L);

    assertEquals("100", decorated.records().get(0).tokenAmount());
    assertEquals("250", decorated.records().get(1).tokenAmount());
    assertEquals("A380币", decorated.records().get(0).tokenBrandName());
    // 金额与余额口径不变（对账/入账仍按最小货币单位）
    assertEquals(100L, decorated.records().get(0).amount());
    assertEquals(5000L, decorated.records().get(0).balanceAfter());
    assertEquals(250L, decorated.records().get(1).amount());
    assertEquals(4750L, decorated.records().get(1).balanceAfter());
    assertEquals("USD", decorated.records().get(0).currencyCode());
    // 分页元信息：total 来自端口，size/current 来自请求参数，pages 由 total/size 推出。
    assertEquals(2L, decorated.total());
    assertEquals(20L, decorated.size());
    assertEquals(1L, decorated.current());
    assertEquals(1L, decorated.pages());
  }

  /**
   * 代币数量绝不参与任何金额合计：补充展示字段后，所有货币字段与金额合计保持逐位不变；
   * 代币值是字符串数量，不含货币符号、也不得写回任何金额字段。
   *
   * <p>原用例断言"原地补字段后原对象金额不变"。视图改成不可变 record 后该语义升级为：
   * {@code decorate} 返回<b>新</b>对象，传入的账户对象本身不可能被改动（无 setter），
   * 因此这里同时断言原对象逐位不变、以及代币只出现在返回的视图里。
   */
  @Test
  void tokenAmountNeverParticipatesInAmountFieldsOrSums() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    WalletAccountView account = account(12345L, 678L);

    long availableBefore = account.availableAmount();
    long frozenBefore = account.frozenAmount();
    WalletAccountView decorated = service.decorate(account);

    // 入参（不可变视图）逐位不变
    assertEquals(availableBefore, account.availableAmount());
    assertEquals(frozenBefore, account.frozenAmount());
    assertEquals("USD", account.currencyCode());
    assertNull(account.tokenAmount(), "展示字段只出现在 decorate 的返回值上");
    // 返回值：货币字段原样，代币是纯数量字符串
    assertEquals(availableBefore, decorated.availableAmount());
    assertEquals(frozenBefore, decorated.frozenAmount());
    assertEquals("USD", decorated.currencyCode());
    assertEquals(12345L + 678L, decorated.availableAmount() + decorated.frozenAmount());
    assertEquals("12345", decorated.tokenAmount());
    assertTrue(decorated.tokenAmount() instanceof String, "代币数量必须是字符串数量，不是金额数值");
    assertTrue(decorated.tokenAmount().chars().allMatch(Character::isDigit), "代币数量不得含货币符号");
  }

  private void withTenant(long tenantId) {
    TenantContextHolder.set(new TenantContext(tenantId, null, null, 1L, 1));
  }

  /** 领域账户 → 响应视图（与控制器同一条转换路径）。 */
  private static WalletAccountView account(Long available, Long frozen) {
    return WalletAccountView.from(new WalletAccount(1L, null, 7L, null, "USD", available, frozen,
        null, null, true, WalletAccount.STATUS_ACTIVE, 0));
  }

  private static WalletLedgerEntry ledger(long amount, long balanceAfter) {
    return new WalletLedgerEntry(amount, null, null, 1L, "RECHARGE", amount, balanceAfter, "USD",
        null, null, null, null, null, LocalDateTime.now());
  }
}
