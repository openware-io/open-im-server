package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.api.dto.MemberPointsView;
import io.openware.platform.customer.api.dto.WalletAccountView;
import io.openware.platform.customer.api.dto.WalletLedgerPageView;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository.LedgerPage;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient.WalletTokenConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 三端共享的响应契约（序列化层）：钱包响应新增的 {@code tokenAmount} 必须是<b>纯数量字符串</b>
 * （无货币符号/币种），原货币字段保持数值语义；积分响应不含币种、不含 tokenAmount。
 *
 * <p>输入是领域模型 / 端口分页，输出是 API 视图（{@link WalletAccountView} /
 * {@link WalletLedgerPageView} / {@link MemberPointsView}）——契约断言因此作用在<b>真正被序列化的那个对象</b>上。
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
    when(client.resolve(TENANT_ID)).thenReturn(new WalletTokenConfig("OpenChat币", 100L));
    WalletAccountView account = displayService.decorate(WalletAccountView.from(
        new WalletAccount(1L, null, 7L, null, "USD", 100L, 0L, null, null, true,
            WalletAccount.STATUS_ACTIVE, 0)));

    String json = objectMapper.writeValueAsString(account);
    JsonNode node = objectMapper.readTree(json);

    assertTrue(node.path("tokenAmount").isTextual(), "tokenAmount 必须是字符串数量");
    assertEquals("100", node.path("tokenAmount").asText());
    assertEquals("OpenChat币", node.path("tokenBrandName").asText());
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
    WalletLedgerEntry row = new WalletLedgerEntry(9L, null, null, 1L, "RECHARGE", 50L, 50L, "USD",
        null, null, null, null, null, LocalDateTime.now());
    WalletLedgerPageView page = displayService.decorate(
        new WalletAccountRepository.LedgerPage(List.of(row), 1L), 1L, 20L);

    JsonNode pageNode = objectMapper.readTree(objectMapper.writeValueAsString(page));
    JsonNode record = pageNode.path("records").get(0);

    assertTrue(record.path("tokenAmount").isTextual());
    assertEquals("100", record.path("tokenAmount").asText()); // 50 最小单位 ÷ 100 × 200 = 100
    assertEquals("皇冠币", record.path("tokenBrandName").asText());
    assertEquals(50L, record.path("amount").asLong());
    assertEquals(50L, record.path("balanceAfter").asLong());
    assertEquals("USD", record.path("currencyCode").asText());
    // 分页外形照搬原 MyBatis-Plus Page 的字段名（records/total/size/current/pages）
    assertEquals(1L, pageNode.path("total").asLong());
    assertEquals(20L, pageNode.path("size").asLong());
    assertEquals(1L, pageNode.path("current").asLong());
    assertEquals(1L, pageNode.path("pages").asLong());
  }

  /** 积分响应：只有积分「个数」，不含币种、不含 tokenAmount（积分既非货币也非储值代币）。 */
  @Test
  void pointsJsonHasNoCurrencyAndNoTokenFields() throws Exception {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1));
    PointAccount account = new PointAccount(1L, TENANT_ID, 7L, 1L, 1200L, 30L, 0);
    PointLedgerEntry persisted = new PointLedgerEntry(5L, TENANT_ID, null, 1L, "ADJUST", 100L, "USD",
        1200L, null, null, "cmd-1", null, null, LocalDateTime.now(), LocalDateTime.now());
    MemberPointsView view = MemberPointsView.from(account,
        new LedgerPage(List.of(persisted), 1L), 1L, 20L);

    String json = objectMapper.writeValueAsString(view);
    JsonNode node = objectMapper.readTree(json);

    assertFalse(json.contains("currencyCode"), "积分响应不得包含币种快照");
    assertFalse(json.contains("currency"), "积分响应不得包含币种字段");
    assertFalse(json.contains("tokenAmount"), "积分响应不得包含代币数量（积分不是储值代币）");
    assertFalse(json.contains("tokenBrandName"), "积分响应不得包含代币品牌名");
    // 积分就是个数：账户余额与流水都是整数个数，1:1 不换算
    assertEquals(1200L, node.path("account").path("availablePoints").asLong());
    assertEquals(30L, node.path("account").path("frozenPoints").asLong());
    assertEquals(100L, node.path("records").get(0).path("points").asLong());
    assertEquals(1200L, node.path("records").get(0).path("balanceAfter").asLong());
    // 分页结构保持原样（前端分页字段不变）
    assertEquals(1L, node.path("total").asLong());
  }

  /**
   * 积分响应<b>字段名逐个保留</b>：账户此前直接返回 {@code CstPointAccountPo}，
   * 视图必须继续给出同样的键（含 tenantId/programId/version 与四个审计列），
   * 否则就是一次没有版本说明的契约破坏。
   *
   * <p>审计时间戳在重构后为 {@code null}（领域模型不承载审计字段），这一点写在交接文档里：
   * 收紧字段集与恢复审计值都要单独一次变更。
   */
  @Test
  void pointsJsonKeepsEveryPersistedFieldName() throws Exception {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1));
    PointAccount account = new PointAccount(1L, TENANT_ID, 7L, 1L, 1200L, 30L, 0);
    PointLedgerEntry row = new PointLedgerEntry(5L, TENANT_ID, 9L, 1L, "EARN", 100L, "USD",
        1200L, "ORDER", 8L, "cmd-2", "{\"earnRate\":1}", null, LocalDateTime.now(), LocalDateTime.now());

    JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(
        MemberPointsView.from(account, new LedgerPage(List.of(row), 1L), 2L, 10L)));
    JsonNode accountNode = node.path("account");

    for (String field : List.of("id", "tenantId", "customerId", "programId", "availablePoints",
        "frozenPoints", "version", "createdBy", "createdAt", "updatedBy", "updatedAt")) {
      assertTrue(accountNode.has(field), "积分账户响应缺少既有字段: " + field);
    }
    JsonNode record = node.path("records").get(0);
    for (String field : List.of("id", "entryType", "points", "balanceAfter", "businessType",
        "businessId", "occurredAt", "createdAt")) {
      assertTrue(record.has(field), "积分流水行缺少既有字段: " + field);
    }
    // 内部字段不得泄漏：幂等键/规则快照/币种只属于账本内部口径。
    assertFalse(record.has("idempotencyKey"), "幂等键不得进入响应");
    assertFalse(record.has("ruleSnapshotJson"), "规则快照不得进入响应");
    // 分页外形（原 MyBatis-Plus Page 的字段名）保持不变。
    assertEquals(1L, node.path("total").asLong());
    assertEquals(10L, node.path("size").asLong());
    assertEquals(2L, node.path("current").asLong());
    assertEquals(1L, node.path("pages").asLong());
  }
}
