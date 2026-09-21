package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A380币储值账本：只追加流水、扣减前校验余额、幂等。 */
class WalletApplicationServiceTest {

  private final WalletAccountMapper accountMapper = mock(WalletAccountMapper.class);
  private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final WalletApplicationService service =
      new WalletApplicationService(accountMapper, ledgerMapper, auditClient);

  @Test
  void recharge_appendsLedgerAndIncreasesBalance() {
    when(accountMapper.selectOne(any())).thenReturn(account(5000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    CstWalletAccountPo result = service.recharge(7L, 2000L, "CNY", "CASH", "R1", "key1");

    assertEquals(7000L, result.getAvailableAmount());
    assertEquals(1, result.getVersion());
    ArgumentCaptor<CstWalletLedgerPo> captor = ArgumentCaptor.forClass(CstWalletLedgerPo.class);
    verify(ledgerMapper).insert(captor.capture());
    assertEquals("RECHARGE", captor.getValue().getEntryType());
    assertEquals(2000L, captor.getValue().getAmount());
    assertEquals(7000L, captor.getValue().getBalanceAfter());
    // 账本自证币种（快照）：与钱包账户币种一致，不再依赖回查账户。
    assertEquals("CNY", captor.getValue().getCurrencyCode());
    // 账本只追加：只 insert，绝不 update/delete 历史流水。
    verify(ledgerMapper, never()).updateById(any(CstWalletLedgerPo.class));
    verify(accountMapper).updateById(result);
  }

  @Test
  void consume_appendsLedgerAndDecreasesBalance() {
    when(accountMapper.selectOne(any())).thenReturn(account(10000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    CstWalletAccountPo result = service.consume(7L, 3000L, "CNY", 9L, "key2");

    assertEquals(7000L, result.getAvailableAmount());
    assertEquals(1, result.getVersion());
    ArgumentCaptor<CstWalletLedgerPo> captor = ArgumentCaptor.forClass(CstWalletLedgerPo.class);
    verify(ledgerMapper).insert(captor.capture());
    assertEquals("CONSUME", captor.getValue().getEntryType());
    assertEquals(3000L, captor.getValue().getAmount());
    assertEquals(7000L, captor.getValue().getBalanceAfter());
    verify(ledgerMapper, never()).updateById(any(CstWalletLedgerPo.class));
    verify(accountMapper).updateById(result);
  }

  @Test
  void consume_rejectsWhenBalanceInsufficient() {
    when(accountMapper.selectOne(any())).thenReturn(account(2000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 3000L, "CNY", 9L, "key3"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(ledgerMapper, never()).insert(any(CstWalletLedgerPo.class));
    verify(accountMapper, never()).updateById(any(CstWalletAccountPo.class));
  }

  @Test
  void consume_throws409OnDuplicateIdempotencyKey() {
    when(accountMapper.selectOne(any())).thenReturn(account(10000L));
    when(ledgerMapper.selectCount(any())).thenReturn(1L);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 3000L, "CNY", 9L, "key2"));

    assertEquals(409, ex.getStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    verify(ledgerMapper, never()).insert(any(CstWalletLedgerPo.class));
    verify(accountMapper, never()).updateById(any(CstWalletAccountPo.class));
  }

  @Test
  void consume_rejectsNonPositiveAmount() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 0L, "CNY", 9L, "key4"));

    assertEquals(400, ex.getStatus());
    assertEquals("AMOUNT_INVALID", ex.getCode());
  }

  @Test
  void consume_requiresIdempotencyKey() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 1000L, "CNY", 9L, " "));

    assertEquals(400, ex.getStatus());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
  }

  @Test
  void consume_rejectsCrossCurrencyCorridor() {
    when(accountMapper.selectOne(any())).thenReturn(account(10000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 1000L, "USD", 9L, "key5"));

    // 跨币种储值消费默认关闭（KTV_BUSINESS_01 / SAAS_PLATFORM_05 的 CURRENCY_CORRIDOR_DISABLED）
    assertEquals(422, ex.getStatus());
    assertEquals("CURRENCY_CORRIDOR_DISABLED", ex.getCode());
    verify(ledgerMapper, never()).insert(any(CstWalletLedgerPo.class));
  }

  /**
   * 懒初始化：没有账户的会员查余额返回**零额只读视图**，不落库、不报 404
   * （「储值管理」此前逐个会员查余额时报的就是 {@code WALLET_ACCOUNT_NOT_FOUND 储值账户不存在}）。
   */
  @Test
  void balanceWithoutAccountReturnsZeroViewAndCreatesNothing() {
    when(accountMapper.selectOne(any())).thenReturn(null);

    CstWalletAccountPo result = service.balance(7L);

    assertEquals(0L, result.getAvailableAmount());
    assertEquals(0L, result.getFrozenAmount());
    assertEquals(7L, result.getCustomerId());
    assertNull(result.getId(), "零额视图不是数据库行，不得被当成真实账户");
    verify(accountMapper, never()).insert(any(CstWalletAccountPo.class));
  }

  /** 懒初始化：没有账户的会员查流水返回空页，既不建账户也不查流水表。 */
  @Test
  void ledgerWithoutAccountReturnsEmptyPageAndCreatesNothing() {
    when(accountMapper.selectOne(any())).thenReturn(null);

    Page<CstWalletLedgerPo> page = service.ledger(7L, 1L, 20L);

    assertTrue(page.getRecords().isEmpty());
    assertEquals(0L, page.getTotal());
    verify(ledgerMapper, never()).selectPage(any(), any());
    verify(accountMapper, never()).insert(any(CstWalletAccountPo.class));
  }

  /** 懒初始化：没有账户 = 余额 0，消费按**余额不足**拒绝（不是 404，也不为失败路径建账户）。 */
  @Test
  void consumeWithoutAccountIsRejectedAsInsufficientBalance() {
    when(accountMapper.selectOne(any())).thenReturn(null);
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 1000L, "CNY", 9L, "key-no-account"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(accountMapper, never()).insert(any(CstWalletAccountPo.class));
    verify(ledgerMapper, never()).insert(any(CstWalletLedgerPo.class));
  }

  /** 懒初始化：没有账户时退也同样按余额不足拒绝（此前是 404「储值账户不存在」）。 */
  @Test
  void refundWithoutAccountIsRejectedAsInsufficientBalance() {
    when(accountMapper.selectOne(any())).thenReturn(null);
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.refund(7L, 100L, "误充", "key-no-account-refund"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(accountMapper, never()).insert(any(CstWalletAccountPo.class));
  }

  /** 懒初始化：补偿性释放（RELEASE）在没有账户时是空操作——没有扣减可归还，不建账户不写流水。 */
  @Test
  void releaseWithoutAccountIsANoOp() {
    when(accountMapper.selectOne(any())).thenReturn(null);

    CstWalletAccountPo result = service.release(7L, 1000L, "CNY", 9L, "key-no-account-release");

    assertEquals(0L, result.getAvailableAmount());
    assertNull(result.getId());
    verify(accountMapper, never()).insert(any(CstWalletAccountPo.class));
    verify(ledgerMapper, never()).insert(any(CstWalletLedgerPo.class));
  }

  /** 储值管理列表：整页会员**一次**批量查询；没有账户的会员不出现在结果里（调用方按 0/未开立处理）。 */
  @Test
  void balancesQueriesOnceAndOmitsMembersWithoutAccount() {
    when(accountMapper.selectList(any())).thenReturn(List.of(account(500L)));

    Map<Long, CstWalletAccountPo> result = service.balances(List.of(7L, 8L));

    assertEquals(1, result.size());
    assertEquals(500L, result.get(7L).getAvailableAmount());
    assertFalse(result.containsKey(8L));
    verify(accountMapper, times(1)).selectList(any());
  }

  private static CstWalletAccountPo account(long available) {
    CstWalletAccountPo po = new CstWalletAccountPo();
    po.setId(1L);
    po.setCustomerId(7L);
    po.setCurrencyCode("CNY");
    po.setAvailableAmount(available);
    po.setFrozenAmount(0L);
    po.setStatus("ACTIVE");
    po.setVersion(0);
    return po;
  }

  /** ③ 失败留痕：储值消费被拒（余额不足）除 422 外必须落一条 FAILURE，错误码可检索。 */
  @Test
  void consumeFailureRecordsFailureAuditWithErrorCode() {
    when(accountMapper.selectOne(any())).thenReturn(account(2000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    assertThrows(ApiException.class, () -> service.consume(7L, 3000L, "CNY", 9L, "key-fail"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.consume", record.action());
    assertEquals("cst_wallet_account", record.resourceType());
    assertEquals("7", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
  }

  /** ③ 失败留痕：跨币种消费被拒（CURRENCY_CORRIDOR_DISABLED）同样必须留痕。 */
  @Test
  void crossCurrencyConsumeFailureIsAudited() {
    when(accountMapper.selectOne(any())).thenReturn(account(10000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    assertThrows(ApiException.class, () -> service.consume(7L, 1000L, "USD", 9L, "key-corridor"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.consume", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("CURRENCY_CORRIDOR_DISABLED", record.errorCode());
  }

  /** ③ 失败留痕：储值充值参数非法（缺币种）也要留痕，errorCode 用既有 ApiException 稳定码。 */
  @Test
  void rechargeFailureRecordsFailureAuditWithErrorCode() {
    assertThrows(ApiException.class, () -> service.recharge(7L, 1000L, "  ", "CASH", "R1", "key-bad"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.recharge", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("CURRENCY_REQUIRED", record.errorCode());
    assertFalse(record.detailJson().contains("amount"), "失败详情不得包含金额字段");
  }

  /** ③ 失败留痕：储值退还余额不足（LEDGER_INSUFFICIENT）必须留痕。 */
  @Test
  void refundInsufficientBalanceIsAudited() {
    when(accountMapper.selectOne(any())).thenReturn(account(100L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    assertThrows(ApiException.class, () -> service.refund(7L, 500L, "误充", "key-refund"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.refund", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
  }

  /** 成功路径不得产生 FAILURE 记录（避免把正常业务操作误报为失败）。 */
  @Test
  void successfulRechargeDoesNotRecordFailure() {
    when(accountMapper.selectOne(any())).thenReturn(account(5000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    service.recharge(7L, 2000L, "CNY", "CASH", "R1", "key-ok");

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.recharge", record.action());
    // 成功路径不显式标记结果，由 SDK buildBody 缺省为 SUCCESS；关键是不能被标成 FAILURE。
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}
