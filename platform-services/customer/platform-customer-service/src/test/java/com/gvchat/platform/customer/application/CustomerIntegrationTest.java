package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
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
 * H2(MODE=MySQL) + 真实 Flyway(测试迁移) + 真实 MyBatis；仅 mock 审计客户端。
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
  private WalletAccountMapper walletAccountMapper;

  @Autowired
  private WalletLedgerMapper walletLedgerMapper;

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
    CstWalletAccountPo first = walletApplicationService.recharge(
        CUSTOMER_ID, 2000L, "CNY", "CASH", "R1", "recharge-key-1");
    assertEquals(2000L, first.getAvailableAmount());
    assertEquals(1, first.getVersion());
    assertNotNull(first.getId());

    CstWalletAccountPo second = walletApplicationService.recharge(
        CUSTOMER_ID, 1000L, "CNY", "CASH", "R2", "recharge-key-2");
    assertEquals(3000L, second.getAvailableAmount());
    assertEquals(2, second.getVersion());
    assertEquals(first.getId(), second.getId());

    // 账本只追加：两次充值 2 条流水，历史流水 balance_after 保持首次余额不变。
    List<CstWalletLedgerPo> ledgers = walletLedgerMapper.selectList(
        new QueryWrapper<CstWalletLedgerPo>().eq("wallet_account_id", first.getId()).orderByAsc("id"));
    assertEquals(2, ledgers.size());
    assertEquals("RECHARGE", ledgers.get(0).getEntryType());
    assertEquals(2000L, ledgers.get(0).getAmount());
    assertEquals(2000L, ledgers.get(0).getBalanceAfter());
    // 币种快照（16_CURRENCY_CONVENTIONS §5）：账本自证币种，落库后与账户币种一致。
    assertEquals("CNY", ledgers.get(0).getCurrencyCode());
    assertEquals("CNY", ledgers.get(1).getCurrencyCode());
    assertEquals("RECHARGE", ledgers.get(1).getEntryType());
    assertEquals(1000L, ledgers.get(1).getAmount());
    assertEquals(3000L, ledgers.get(1).getBalanceAfter());

    // 账户行仍仅一行（同主体同币种复用），未因重复充值新增账户。
    Long accountCount = walletAccountMapper.selectCount(
        new QueryWrapper<CstWalletAccountPo>().eq("customer_id", CUSTOMER_ID));
    assertEquals(1L, accountCount);
  }
}
