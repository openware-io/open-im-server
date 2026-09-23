package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 钱包「代币数量」展示口径：{@code 代币数量 = 余额最小单位 ÷ 100 × ratio}（四舍五入取整，ratio 缺省 100）。
 *
 * <p>断言三件事：①换算值正确（含 0 余额、冻结不影响可用口径、ratio 非法回退缺省、四舍五入）；
 * ②代币字段是<b>纯数量字符串</b>，不携带币种/货币符号；③代币数量绝不参与任何金额字段与金额合计。
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

    CstWalletAccountPo account = service.decorate(account(100L, 0L));

    assertEquals("100", account.getTokenAmount());
    assertEquals("皇冠币", account.getTokenBrandName());
  }

  /** ratio 缺省（缺配置/调用失败都退化成 100）：250 最小单位 → 250 个代币。 */
  @Test
  void usesDefaultRatioWhenTenantConfigFallsBack() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);

    CstWalletAccountPo account = service.decorate(account(250L, 0L));

    assertEquals("250", account.getTokenAmount());
    assertEquals("A380币", account.getTokenBrandName());
  }

  /** 余额 0 → 0 个代币（不产生空值/异常）。 */
  @Test
  void zeroBalanceYieldsZeroTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));

    assertEquals("0", service.decorate(account(0L, 0L)).getTokenAmount());
  }

  /** 冻结金额不影响可用代币展示口径：可用 100 → 100 个代币（冻结金额字段原样保留）。 */
  @Test
  void frozenAmountDoesNotAffectAvailableTokenDisplay() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));

    CstWalletAccountPo account = service.decorate(account(100L, 999999L));

    assertEquals("100", account.getTokenAmount());
    assertEquals(999999L, account.getFrozenAmount());
  }

  /** ratio 非法（非正）由本服务兜底回退缺省 100，不让换算结果变成 0 或负数。 */
  @Test
  void invalidRatioFallsBackToDefault() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 0L));
    assertEquals("100", service.decorate(account(100L, 0L)).getTokenAmount());

    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", -5L));
    assertEquals("100", service.decorate(account(100L, 0L)).getTokenAmount());
  }

  /** 四舍五入取整：150 最小单位 × ratio 33 = 49.5 → 50。 */
  @Test
  void roundsHalfUpToWholeTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 33L));

    assertEquals("50", service.decorate(account(150L, 0L)).getTokenAmount());
    assertEquals("0", service.decorate(account(1L, 0L)).getTokenAmount());
  }

  /** 金额为 null（异常数据）按 0 处理，不抛异常。 */
  @Test
  void nullAmountYieldsZeroTokens() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(WalletTokenConfig.DEFAULT);

    assertEquals("0", service.decorate(account(null, 0L)).getTokenAmount());
  }

  /** 无租户上下文：不查询租户配置（tenantId=null），品牌/比例回退缺省。 */
  @Test
  void missingTenantContextFallsBackToDefaults() {
    TenantContextHolder.clear();
    when(client.resolve(null)).thenReturn(WalletTokenConfig.DEFAULT);

    CstWalletAccountPo account = service.decorate(account(100L, 0L));

    verify(client).resolve(null);
    assertEquals("A380币", account.getTokenBrandName());
    assertEquals("100", account.getTokenAmount());
  }

  /** 流水行按本笔发生额换算代币数量，分页元信息与金额字段原样保留。 */
  @Test
  void decoratesLedgerPageByEntryAmount() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    Page<CstWalletLedgerPo> page = ledgerPage(ledger(100L, 5000L), ledger(250L, 4750L));

    Page<CstWalletLedgerPo> decorated = service.decorate(page);

    assertSame(page, decorated); // 原地补充展示字段
    assertEquals("100", decorated.getRecords().get(0).getTokenAmount());
    assertEquals("250", decorated.getRecords().get(1).getTokenAmount());
    assertEquals("A380币", decorated.getRecords().get(0).getTokenBrandName());
    // 金额与余额口径不变（对账/入账仍按最小货币单位）
    assertEquals(100L, decorated.getRecords().get(0).getAmount());
    assertEquals(5000L, decorated.getRecords().get(0).getBalanceAfter());
    assertEquals(250L, decorated.getRecords().get(1).getAmount());
    assertEquals(4750L, decorated.getRecords().get(1).getBalanceAfter());
    assertEquals("USD", decorated.getRecords().get(0).getCurrencyCode());
    assertEquals(2L, decorated.getTotal());
  }

  /**
   * 代币数量绝不参与任何金额合计：补充展示字段后，所有货币字段与金额合计保持逐位不变；
   * 代币值是字符串数量，不含货币符号、也不得写回任何金额字段。
   */
  @Test
  void tokenAmountNeverParticipatesInAmountFieldsOrSums() {
    withTenant(100L);
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    CstWalletAccountPo account = account(12345L, 678L);

    long availableBefore = account.getAvailableAmount();
    long frozenBefore = account.getFrozenAmount();
    service.decorate(account);

    assertEquals(availableBefore, account.getAvailableAmount());
    assertEquals(frozenBefore, account.getFrozenAmount());
    assertEquals("USD", account.getCurrencyCode());
    assertEquals(12345L + 678L, account.getAvailableAmount() + account.getFrozenAmount());
    assertEquals("12345", account.getTokenAmount());
    assertTrue(account.getTokenAmount() instanceof String, "代币数量必须是字符串数量，不是金额数值");
    assertTrue(account.getTokenAmount().chars().allMatch(Character::isDigit), "代币数量不得含货币符号");
  }

  private void withTenant(long tenantId) {
    TenantContextHolder.set(new TenantContext(tenantId, null, null, 1L, 1));
  }

  private static CstWalletAccountPo account(Long available, Long frozen) {
    CstWalletAccountPo po = new CstWalletAccountPo();
    po.setId(1L);
    po.setCustomerId(7L);
    po.setCurrencyCode("USD");
    po.setAvailableAmount(available);
    po.setFrozenAmount(frozen);
    po.setStatus("ACTIVE");
    po.setVersion(0);
    return po;
  }

  private static CstWalletLedgerPo ledger(long amount, long balanceAfter) {
    CstWalletLedgerPo po = new CstWalletLedgerPo();
    po.setId(amount);
    po.setWalletAccountId(1L);
    po.setEntryType("RECHARGE");
    po.setAmount(amount);
    po.setBalanceAfter(balanceAfter);
    po.setCurrencyCode("USD");
    return po;
  }

  private static Page<CstWalletLedgerPo> ledgerPage(CstWalletLedgerPo... rows) {
    Page<CstWalletLedgerPo> page = new Page<>(1, 20, rows.length);
    page.setRecords(List.of(rows));
    return page;
  }
}
