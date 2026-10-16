package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 会员储值核心写路径集成测试（真跑）：储值充值（账本只追加，不更新历史流水）。
 * 连续两次充值验证余额累加、版本递增，且历史流水 balance_after 保持不变（只追加）。
 * H2(MODE=MySQL) + 真实 Flyway(测试迁移) + 真实 MyBatis + 真实仓储适配器；仅 mock 审计客户端。
 *
 * <p><b>读取一律走领域端口</b>（{@link WalletAccountRepository}）：本用例验的是"落库后的领域事实"
 * （余额、版本、流水条数与内容、账户行数），不是 SQL 形状；SQL/列的保真度由适配器测试覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class CustomerIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long CUSTOMER_ID = 7001L;

  @MockitoBean
  private AuditClient auditClient;

  @Autowired
  private WalletApplicationService walletApplicationService;

  @Autowired
  private WalletAccountRepository walletAccountRepository;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, 2001L, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void recharge_appendsLedgerOnlyAndAccumulatesBalance() {
    WalletAccount first = walletApplicationService.recharge(
        CUSTOMER_ID, 2000L, "CNY", "CASH", "R1", "recharge-key-1");
    assertEquals(2000L, first.available());
    assertEquals(1, first.currentVersion());
    assertNotNull(first.id());

    WalletAccount second = walletApplicationService.recharge(
        CUSTOMER_ID, 1000L, "CNY", "CASH", "R2", "recharge-key-2");
    assertEquals(3000L, second.available());
    assertEquals(2, second.currentVersion());
    assertEquals(first.id(), second.id());

    // 账本只追加：两次充值 2 条流水，历史流水 balance_after 保持首次余额不变。
    LedgerPage page = walletAccountRepository.listLedger(first.id(), null, 0L, 20L);
    List<WalletLedgerEntry> ledgers = page.entries();
    assertEquals(2L, page.total());
    assertEquals(2, ledgers.size());
    // 端口按 id 倒序返回，因此 [0] 是最近一次充值。
    assertEquals("RECHARGE", ledgers.get(1).entryType());
    assertEquals(2000L, ledgers.get(1).amount());
    assertEquals(2000L, ledgers.get(1).balanceAfter());
    // 币种快照（16_CURRENCY_CONVENTIONS §5）：账本自证币种，落库后与账户币种一致。
    assertEquals("CNY", ledgers.get(1).currencyCode());
    assertEquals("CNY", ledgers.get(0).currencyCode());
    assertEquals("RECHARGE", ledgers.get(0).entryType());
    assertEquals(1000L, ledgers.get(0).amount());
    assertEquals(3000L, ledgers.get(0).balanceAfter());

    // 账户行仍仅一行（同主体同币种复用），未因重复充值新增账户。
    assertEquals(1, walletAccountRepository.findByCustomers(List.of(CUSTOMER_ID)).size());
  }
}
