package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 三端共享的响应契约（序列化层）：钱包响应新增的 {@code tokenAmount} 必须是<b>纯数量字符串</b>
 * （无货币符号/币种），原货币字段保持数值语义；积分响应不含币种、不含 tokenAmount。
 */
class WalletTokenResponseContractTest {

  private static final long TENANT_ID = 100L;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);
  private final WalletTokenDisplayService displayService = new WalletTokenDisplayService(client);

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 钱包账户：100 分（USD cent）× ratio 100 → tokenAmount "100"，货币字段不变。 */
  @Test
  void walletAccountJsonCarriesTokenQuantityAndKeepsMoneyFields() throws Exception {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1));
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("A380币", 100L));
    CstWalletAccountPo account = new CstWalletAccountPo();
    account.setId(1L);
    account.setCustomerId(7L);
    account.setCurrencyCode("USD");
    account.setAvailableAmount(100L);
    account.setFrozenAmount(0L);
    displayService.decorate(account);

    String json = objectMapper.writeValueAsString(account);
    JsonNode node = objectMapper.readTree(json);

    assertTrue(node.path("tokenAmount").isTextual(), "tokenAmount 必须是字符串数量");
    assertEquals("100", node.path("tokenAmount").asText());
    assertEquals("A380币", node.path("tokenBrandName").asText());
    // 原货币字段不变：仍是「这笔钱」的最小货币单位与币种（对账/入账口径）
    assertEquals(100L, node.path("availableAmount").asLong());
    assertEquals(0L, node.path("frozenAmount").asLong());
    assertEquals("USD", node.path("currencyCode").asText());
    // 代币是数量不是钱：响应里不得出现货币符号
    assertFalse(json.contains("$"), "代币数量不得携带货币符号");
    assertFalse(json.contains("¥"), "代币数量不得携带货币符号");
  }

  /** 钱包流水：每行按本笔发生额换算数量；金额/余额字段仍是最小货币单位整数。 */
  @Test
  void walletLedgerJsonCarriesTokenQuantityPerRow() throws Exception {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1));
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("皇冠币", 200L));
    Page<CstWalletLedgerPo> page = new Page<>(1, 20, 1);
    CstWalletLedgerPo row = new CstWalletLedgerPo();
    row.setId(9L);
    row.setEntryType("RECHARGE");
    row.setAmount(50L);
    row.setBalanceAfter(50L);
    row.setCurrencyCode("USD");
    page.setRecords(List.of(row));
    displayService.decorate(page);

    JsonNode record = objectMapper.readTree(objectMapper.writeValueAsString(page))
        .path("records").get(0);

    assertTrue(record.path("tokenAmount").isTextual());
    assertEquals("100", record.path("tokenAmount").asText()); // 50 最小单位 ÷ 100 × 200 = 100
    assertEquals("皇冠币", record.path("tokenBrandName").asText());
    assertEquals(50L, record.path("amount").asLong());
    assertEquals(50L, record.path("balanceAfter").asLong());
    assertEquals("USD", record.path("currencyCode").asText());
  }

  /** 积分响应：只有积分「个数」，不含币种、不含 tokenAmount（积分既非货币也非储值代币）。 */
  @Test
  void pointsJsonHasNoCurrencyAndNoTokenFields() throws Exception {
    CstPointAccountPo account = new CstPointAccountPo();
    account.setId(1L);
    account.setCustomerId(7L);
    account.setProgramId(1L);
    account.setAvailablePoints(1200L);
    account.setFrozenPoints(30L);

    CstPointLedgerPo persisted = new CstPointLedgerPo();
    persisted.setId(5L);
    persisted.setAccountId(1L);
    persisted.setEntryType("ADJUST");
    persisted.setPoints(100L);
    persisted.setCurrencyCode("USD");
    persisted.setBalanceAfter(1200L);
    Page<PointLedgerRow> ledger = new Page<>(1, 20, 1);
    ledger.setRecords(List.of(PointLedgerRow.of(persisted)));

    String json = objectMapper.writeValueAsString(new MemberPointsView(account, ledger));
    JsonNode node = objectMapper.readTree(json);

    assertFalse(json.contains("currencyCode"), "积分响应不得包含币种快照");
    assertFalse(json.contains("currency"), "积分响应不得包含币种字段");
    assertFalse(json.contains("tokenAmount"), "积分响应不得包含代币数量（积分不是储值代币）");
    assertFalse(json.contains("tokenBrandName"), "积分响应不得包含代币品牌名");
    // 积分就是个数：账户余额与流水都是整数个数，1:1 不换算
    assertEquals(1200L, node.path("account").path("availablePoints").asLong());
    assertEquals(30L, node.path("account").path("frozenPoints").asLong());
    assertEquals(100L, node.path("ledger").path("records").get(0).path("points").asLong());
    assertEquals(1200L, node.path("ledger").path("records").get(0).path("balanceAfter").asLong());
    // 分页结构保持原样（前端分页字段不变）
    assertEquals(1L, node.path("ledger").path("total").asLong());
  }
}
