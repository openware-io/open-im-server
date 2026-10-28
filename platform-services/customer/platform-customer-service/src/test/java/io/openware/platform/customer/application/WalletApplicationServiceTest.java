package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

/**
 * SaaS 币储值账本：只追加流水、扣减前校验余额、幂等。
 *
 * <p><b>本类只断言「端口调用语义」</b>（standards/10「测试分层」）：用什么 SQL、写了哪些列属于适配器
 * 的职责，由 {@code WalletAccountRepositoryAdapterTest} 用真实适配器 + mock mapper 覆盖。
 * 这里 mock {@link WalletAccountRepository}，断言"调了哪个端口方法、传进去的领域对象是什么"。
 */
class WalletApplicationServiceTest {

  /** 库存账户的乐观锁版本：CAS 断言要求「读出来的版本」被原样传回端口。 */
  private static final int STORED_VERSION = 3;

  @AfterEach
  void clearContext() { TenantContextHolder.clear(); }

  private final WalletAccountRepository repository = mock(WalletAccountRepository.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final WalletApplicationService service =
      new WalletApplicationService(repository, auditClient);

  /**
   * CAS 成功是**成功路径的前提**：{@link WalletAccountRepository#updateBalanceWithVersion} 返回
   * {@code false} 表示期间已被他人写入，服务会重新读账户重算或拒绝。默认桩成 {@code true}，
   * 让"正常一次写入"的用例只表达业务意图；CAS 冲突本身由专有端口语义用例覆盖。
   */
  @BeforeEach
  void stubCasSuccess() {
    when(repository.updateBalanceWithVersion(any(), any(Integer.class))).thenReturn(true);
  }

  @Test
  void recharge_appendsLedgerAndIncreasesBalance() {
    when(repository.findByCustomerAndCurrency(7L, "CNY"))
        .thenReturn(Optional.of(account(5000L)));
    when(repository.ledgerExists("key1")).thenReturn(false);

    WalletAccount result = service.recharge(7L, 2000L, "CNY", "CASH", "R1", "key1");

    assertEquals(7000L, result.available());
    // 落库返回的版本 = 写入后的版本（CAS 已在库里自增），调用方拿到的就是最新版本。
    assertEquals(STORED_VERSION + 1, result.currentVersion());
    // 余额写入必须带版本条件（CAS）：无条件 updateById 正是本次修掉的缺陷——两个并发扣减都判定
    // "够扣"、各自写回自己的结果，一笔钱被花两次而账本只记一次。
    ArgumentCaptor<WalletAccount> accountCaptor = ArgumentCaptor.forClass(WalletAccount.class);
    verify(repository).updateBalanceWithVersion(accountCaptor.capture(), eq(STORED_VERSION));
    assertEquals(7000L, accountCaptor.getValue().available());
    ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
    verify(repository).insertLedger(captor.capture());
    assertEquals("RECHARGE", captor.getValue().entryType());
    assertEquals(2000L, captor.getValue().amount());
    assertEquals(7000L, captor.getValue().balanceAfter());
    // 账本自证币种（快照）：与钱包账户币种一致，不再依赖回查账户。
    assertEquals("CNY", captor.getValue().currencyCode());
    // 账本只追加。端口本身**没有**"改/删一条历史流水"的方法（写流水的出口只有 insertLedger），
    // 因此这里不再断言"没调 updateById"：那种断言锁的是已删除的 Mapper 实现细节。
    // 结构性保证已由端口形状给出；本用例只再确认这是一次"纯追加"的余额变更（恰好一次 CAS + 一条流水）。
    verify(repository, times(1)).updateBalanceWithVersion(any(), any(Integer.class));
    verify(repository, times(1)).insertLedger(any(WalletLedgerEntry.class));
  }

  @Test
  void rechargeRecordsSignedStoreOnLedger() {
    when(repository.findByCustomerAndCurrency(7L, "CNY"))
        .thenReturn(Optional.of(account(5000L)));
    when(repository.ledgerExists("key-store")).thenReturn(false);

    service.recharge(77L, 7L, 2000L, "CNY", "CASH", "R1", "key-store");

    ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
    verify(repository).insertLedger(captor.capture());
    assertEquals(77L, captor.getValue().storeId());
  }

  @Test
  void consume_appendsLedgerAndDecreasesBalance() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(10000L)));
    when(repository.ledgerExists("key2")).thenReturn(false);

    WalletAccount result = service.consume(7L, 3000L, "CNY", 9L, "key2");

    assertEquals(7000L, result.available());
    assertEquals(STORED_VERSION + 1, result.currentVersion());
    ArgumentCaptor<WalletAccount> accountCaptor = ArgumentCaptor.forClass(WalletAccount.class);
    verify(repository).updateBalanceWithVersion(accountCaptor.capture(), eq(STORED_VERSION));
    assertEquals(7000L, accountCaptor.getValue().available());
    ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
    verify(repository).insertLedger(captor.capture());
    assertEquals("CONSUME", captor.getValue().entryType());
    assertEquals(3000L, captor.getValue().amount());
    assertEquals(7000L, captor.getValue().balanceAfter());
  }

  @Test
  void consume_rejectsWhenBalanceInsufficient() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(2000L)));
    when(repository.ledgerExists("key3")).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 3000L, "CNY", 9L, "key3"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(repository, never()).insertLedger(any(WalletLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), any(Integer.class));
  }

  @Test
  void consume_throws409OnDuplicateIdempotencyKey() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(10000L)));
    when(repository.ledgerExists("key2")).thenReturn(true);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 3000L, "CNY", 9L, "key2"));

    assertEquals(409, ex.getStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    verify(repository, never()).insertLedger(any(WalletLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), any(Integer.class));
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
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(10000L)));
    when(repository.ledgerExists("key5")).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 1000L, "USD", 9L, "key5"));

    // 跨币种储值消费默认关闭（KTV_BUSINESS_01 / SAAS_PLATFORM_05 的 CURRENCY_CORRIDOR_DISABLED）
    assertEquals(422, ex.getStatus());
    assertEquals("CURRENCY_CORRIDOR_DISABLED", ex.getCode());
    verify(repository, never()).insertLedger(any(WalletLedgerEntry.class));
  }

  /**
   * 懒初始化：没有账户的会员查余额返回**零额只读视图**，不落库、不报 404
   * （「储值管理」此前逐个会员查余额时报的就是 {@code WALLET_ACCOUNT_NOT_FOUND 储值账户不存在}）。
   */
  @Test
  void balanceWithoutAccountReturnsZeroViewAndCreatesNothing() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());

    WalletAccount result = service.balance(7L);

    assertEquals(0L, result.available());
    assertEquals(0L, result.frozen());
    assertEquals(7L, result.customerId());
    assertNull(result.id(), "零额视图不是数据库行，不得被当成真实账户");
    // 读路径不建账户。端口上"开立账户"的唯一出口是 insert(WalletAccount)，没有 mapper 可误用。
    verify(repository, never()).insert(any(WalletAccount.class));
  }

  /** 懒初始化：没有账户的会员查流水返回空页，既不建账户也不查流水表。 */
  @Test
  void ledgerWithoutAccountReturnsEmptyPageAndCreatesNothing() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());

    LedgerPage page = service.ledger(7L, 1L, 20L);

    assertTrue(page.entries().isEmpty());
    assertEquals(0L, page.total());
    verify(repository, never()).listLedger(any(), any(), any(Long.class), any(Long.class));
    verify(repository, never()).countLedger(any(), any());
    verify(repository, never()).insert(any(WalletAccount.class));
  }

  /** 懒初始化：没有账户 = 余额 0，消费按**余额不足**拒绝（不是 404，也不为失败路径建账户）。 */
  @Test
  void consumeWithoutAccountIsRejectedAsInsufficientBalance() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());
    when(repository.ledgerExists("key-no-account")).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.consume(7L, 1000L, "CNY", 9L, "key-no-account"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(repository, never()).insert(any(WalletAccount.class));
    verify(repository, never()).insertLedger(any(WalletLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), any(Integer.class));
  }

  /** 懒初始化：没有账户时退也同样按余额不足拒绝（此前是 404「储值账户不存在」）。 */
  @Test
  void refundWithoutAccountIsRejectedAsInsufficientBalance() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());
    when(repository.ledgerExists("key-no-account-refund")).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.refund(7L, 100L, "误充", "key-no-account-refund"));

    assertEquals(422, ex.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    verify(repository, never()).insert(any(WalletAccount.class));
  }

  /** 懒初始化：补偿性释放（RELEASE）在没有账户时是空操作——没有扣减可归还，不建账户不写流水。 */
  @Test
  void releaseWithoutAccountIsANoOp() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());

    WalletAccount result = service.release(7L, 1000L, "CNY", 9L, "key-no-account-release");

    assertEquals(0L, result.available());
    assertNull(result.id());
    verify(repository, never()).insert(any(WalletAccount.class));
    verify(repository, never()).insertLedger(any(WalletLedgerEntry.class));
  }

  /** 储值管理列表：整页会员**一次**批量查询；没有账户的会员不出现在结果里（调用方按 0/未开立处理）。 */
  @Test
  void balancesQueriesOnceAndOmitsMembersWithoutAccount() {
    // 适配器一次 IN 查询取回整页账户，键为会员 ID；会员 8 没有账户，因此不在 Map 里。
    when(repository.findByCustomers(List.of(7L, 8L))).thenReturn(Map.of(7L, account(500L)));

    Map<Long, WalletAccount> result = service.balances(List.of(7L, 8L));

    assertEquals(1, result.size());
    assertEquals(500L, result.get(7L).available());
    assertFalse(result.containsKey(8L));
    verify(repository, times(1)).findByCustomers(List.of(7L, 8L));
    // 不逐会员查余额：批量口径必须是端口上那一次 findByCustomers。
    verify(repository, never()).findByCustomer(any(Long.class));
  }

  /** 库存账户 fixture：版本 {@link #STORED_VERSION}，让 CAS 断言有确定的期望版本。 */
  private static WalletAccount account(long available) {
    return new WalletAccount(1L, 100L, 7L, 1L, "CNY", available, 0L,
        null, null, true, WalletAccount.STATUS_ACTIVE, STORED_VERSION);
  }

  /** ③ 失败留痕：储值消费被拒（余额不足）除 422 外必须落一条 FAILURE，错误码可检索。 */
  @Test
  void consumeFailureRecordsFailureAuditWithErrorCode() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(2000L)));
    when(repository.ledgerExists("key-fail")).thenReturn(false);

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
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(10000L)));
    when(repository.ledgerExists("key-corridor")).thenReturn(false);

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
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(100L)));
    when(repository.ledgerExists("key-refund")).thenReturn(false);

    assertThrows(ApiException.class, () -> service.refund(7L, 500L, "误充", "key-refund"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("wallet.refund", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
  }

  /** 成功路径不得产生 FAILURE 记录（避免把正常业务操作误报为失败）。 */
  @Test
  void successfulRechargeDoesNotRecordFailure() {
    when(repository.findByCustomerAndCurrency(7L, "CNY"))
        .thenReturn(Optional.of(account(5000L)));
    when(repository.ledgerExists("key-ok")).thenReturn(false);

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
