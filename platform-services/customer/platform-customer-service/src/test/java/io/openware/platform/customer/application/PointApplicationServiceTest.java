package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;

/**
 * 积分账户写操作的失败留痕回归（③ 遗留）：领域服务内部的失败出口此前没有审计记录，
 * 只有 BFF 路径由拦截器兜底。这里断言「业务异常照旧抛出 + 同时落一条 FAILURE 且带稳定错误码」。
 *
 * <p><b>本类只断言「端口调用语义」</b>（standards/10「测试分层」）：用什么 SQL、写了哪些列属于适配器的
 * 职责，由 {@code PointAccountRepositoryAdapterTest} 用真实适配器 + mock mapper 覆盖
 * （迁移前服务测试里那条 SQL 形状断言已随之迁走，不是删掉）。这里 mock
 * {@link PointAccountRepository}，断言"调了哪个端口方法、传进去的领域对象是什么"。
 */
class PointApplicationServiceTest {

  /** 库存账户的乐观锁版本：CAS 断言要求「读出来的版本」被原样传回端口。 */
  private static final int STORED_VERSION = 4;

  /**
   * CAS 成功是<b>成功路径的前提</b>：{@link PointAccountRepository#updateBalanceWithVersion}
   * 返回 {@code false} 表示期间已被他人写入，服务必须回 409 而不是当作成功。默认桩成 {@code true}，
   * 让"正常一次写入"的用例只表达业务意图；CAS 冲突本身由专有端口语义用例覆盖。
   */
  @BeforeEach
  void stubCasSuccessAndTenant() {
    when(repository.updateBalanceWithVersion(any(), anyInt())).thenReturn(true);
    // 账本币种快照取当时租户币种（CurrencyResolver），没有上下文时回退缺省币种；显式给一个稳定口径。
    TenantContextHolder.set(new TenantContext(100L, 1L, 2001L, 0L, 1));
  }

  @AfterEach
  void clearContext() { TenantContextHolder.clear(); }

  private final PointAccountRepository repository = mock(PointAccountRepository.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final PointApplicationService service =
      new PointApplicationService(repository, auditClient);

  @Test
  void adjustRejectsZeroPointsAndRecordsFailure() {
    ApiException failure = assertThrows(ApiException.class,
        () -> service.adjust(7L, 0L, "手工调整", "cmd-1"));

    assertEquals("POINTS_INVALID", failure.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("points.adjust", record.action());
    assertEquals("cst_point_account", record.resourceType());
    assertEquals("7", record.resourceId());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("POINTS_INVALID", record.errorCode());
  }

  @Test
  void adjustRejectsMissingCommandIdAndRecordsFailure() {
    assertThrows(ApiException.class, () -> service.adjust(7L, 100L, "手工调整", "  "));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("points.adjust", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("COMMAND_ID_REQUIRED", record.errorCode());
  }

  @Test
  void adjustRejectsInsufficientPointsAndRecordsFailure() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(10L)));
    when(repository.ledgerExists("cmd-2")).thenReturn(false);

    assertThrows(ApiException.class, () -> service.adjust(7L, -50L, "扣减", "cmd-2"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
    // 失败留痕不得改动账本，也不得改余额：余额不足必须在任何写入之前拒绝。
    //
    // 迁移前这条断言是 verify(ledgerMapper, never()).insert(...)，锁的是已删除的 Mapper 实现细节；
    // 「账本只追加」现在是端口形状给出的结构性保证（写流水的唯一出口是 appendLedger），
    // 因此这里改为断言"两个写入端口都没被调用"——它同时覆盖了流水与余额。
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 成功路径只留成功记录（不得混入 FAILURE）。 */
  @Test
  void adjustSuccessRecordsSuccessAudit() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-3")).thenReturn(false);

    PointAccount saved = service.adjust(7L, 100L, "补偿", "cmd-3");

    assertEquals(1100L, saved.available());
    // 落库返回的版本 = 写入后的版本（CAS 已在库里自增），调用方拿到的就是最新版本。
    assertEquals(STORED_VERSION + 1, saved.currentVersion());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("points.adjust", record.action());
    // 成功路径不显式标记结果，由 SDK buildBody 缺省为 SUCCESS；关键是不能被标成 FAILURE。
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("cmd-3", record.idempotencyKey());
  }

  /**
   * <b>写入顺序：CAS 成功之后才追加流水</b>。
   *
   * <p>迁移前是「先 appendLedger 再 updateAccountOptimistic」，只靠外层 {@code @Transactional}
   * 在 CAS 抛错时把流水滚掉；一旦有人把这段挪出事务，账本就会记着"积分动过"而余额没动，
   * 而<b>对账只能相信账本</b>。这条断言把顺序锁进测试：它会在旧顺序下失败。
   */
  @Test
  void adjustWritesBalanceBeforeLedger() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-order")).thenReturn(false);

    service.adjust(7L, 100L, "补偿", "cmd-order");

    InOrder order = inOrder(repository);
    order.verify(repository).updateBalanceWithVersion(any(PointAccount.class), eq(STORED_VERSION));
    order.verify(repository).appendLedger(any(PointLedgerEntry.class));
  }

  /**
   * CAS 冲突（受影响 0 行）必须 409 且<b>不写任何流水</b>：
   * 反过来先写流水就会留下"积分动过"的账本行，而余额没动。
   */
  @Test
  void casConflictIs409AndLeavesNoLedgerRow() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-conflict")).thenReturn(false);
    when(repository.updateBalanceWithVersion(any(), anyInt())).thenReturn(false);

    ApiException failure = assertThrows(ApiException.class,
        () -> service.adjust(7L, 100L, "补偿", "cmd-conflict"));

    assertEquals(409, failure.getStatus());
    assertEquals("POINT_ACCOUNT_VERSION_CONFLICT", failure.getCode());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
  }

  /** 成功写入必须带版本条件：期望版本就是读出来的版本（无条件写回正是本表要避免的覆盖）。 */
  @Test
  void adjustPassesReadVersionToCas() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-version")).thenReturn(false);

    service.adjust(7L, 100L, "补偿", "cmd-version");

    ArgumentCaptor<PointAccount> captor = ArgumentCaptor.forClass(PointAccount.class);
    verify(repository).updateBalanceWithVersion(captor.capture(), eq(STORED_VERSION));
    assertEquals(1100L, captor.getValue().available());
    assertEquals(7L, captor.getValue().customerId());
    assertEquals(11L, captor.getValue().id());
  }

  /** 账本行由领域条目装配：类型/变动/变动后余额/币种快照/幂等键都要落在流水上。 */
  @Test
  void adjustAppendsLedgerRowFromDomainEntry() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-ledger")).thenReturn(false);

    service.adjust(44L, 7L, 100L, "补偿", "cmd-ledger");

    ArgumentCaptor<PointLedgerEntry> captor = ArgumentCaptor.forClass(PointLedgerEntry.class);
    verify(repository).appendLedger(captor.capture());
    PointLedgerEntry row = captor.getValue();
    assertEquals("ADJUST", row.entryType());
    assertEquals(100L, row.pointsOrZero());
    assertEquals(1100L, row.balanceAfterOrZero());
    assertEquals(11L, row.accountId());
    assertEquals(44L, row.storeId());
    assertEquals("cmd-ledger", row.idempotencyKey());
    // 积分不是货币，但每笔变动都固化当时租户币种快照（报表/审计按币种归集）。
    assertFalse(row.currencyCode() == null || row.currencyCode().isBlank(), "币种快照必须落库");
  }

  @Test
  void storeAttributedAdjustmentAppendsFactEvent() {
    CustomerEventOutbox eventOutbox = mock(CustomerEventOutbox.class);
    PointApplicationService serviceWithOutbox =
        new PointApplicationService(repository, auditClient, eventOutbox);
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-store-1")).thenReturn(false);

    serviceWithOutbox.adjust(44L, 7L, 100L, "补偿", "cmd-store-1");

    ArgumentCaptor<CustomerFactEvent> captor = ArgumentCaptor.forClass(CustomerFactEvent.class);
    verify(eventOutbox).append(captor.capture());
    assertEquals("customer.points.changed", captor.getValue().eventType());
    assertTrue(captor.getValue().payloadJson().contains("\"storeId\":44"));
  }

  /** 懒创建：账户不存在时先建（默认计划 1、零额、版本 0），再在同一路径上写入。 */
  @Test
  void adjustCreatesAccountWhenMissing() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());
    when(repository.insert(any(PointAccount.class))).thenAnswer(invocation -> {
      PointAccount candidate = invocation.getArgument(0);
      return new PointAccount(11L, 100L, candidate.customerId(), candidate.programId(), 0L, 0L, 0);
    });
    when(repository.ledgerExists("cmd-lazy")).thenReturn(false);

    service.adjust(7L, 100L, "首次调整", "cmd-lazy");

    ArgumentCaptor<PointAccount> inserted = ArgumentCaptor.forClass(PointAccount.class);
    verify(repository).insert(inserted.capture());
    assertEquals(7L, inserted.getValue().customerId());
    assertEquals(PointAccount.DEFAULT_PROGRAM_ID, inserted.getValue().programId());
    assertEquals(0L, inserted.getValue().available());
  }

  /** 幂等预检：幂等键已存在 → 409 且不写任何东西（唯一键 uk_cst_point_ledger_idem 兜底并发同键）。 */
  @Test
  void adjustRejectsDuplicateCommandIdWithoutWriting() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("cmd-dup")).thenReturn(true);

    ApiException failure = assertThrows(ApiException.class,
        () -> service.adjust(7L, 100L, "补偿", "cmd-dup"));

    assertEquals(409, failure.getStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 抵扣被规则上限拒绝时不得改余额、不得写流水，且需要留痕。 */
  @Test
  void redeemOverRuleCapIsRejectedWithoutWriting() {
    PointRuleConfigApplicationService ruleService = mock(PointRuleConfigApplicationService.class);
    when(ruleService.resolve(any(), any(), any())).thenReturn(
        new PointRuleConfigApplicationService.PointRule(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
            0, 50L, 0, 1L, "", 0L));
    PointApplicationService capped = new PointApplicationService(repository, auditClient,
        CustomerEventOutbox.disabled(), ruleService);
    when(repository.ledgerExists("key-cap")).thenReturn(false);

    ApiException failure = assertThrows(ApiException.class,
        () -> capped.redeem(7L, 100L, 9L, "key-cap"));

    assertEquals(422, failure.getStatus());
    assertEquals("POINT_REDEEM_CAP_EXCEEDED", failure.getCode());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 抵扣：REDEEM 流水记负数，余额按抵扣后写入。 */
  @Test
  void redeemAppendsNegativeLedgerRowAndWritesBalance() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("key-redeem")).thenReturn(false);

    PointAccount saved = service.redeem(7L, 300L, 9L, "key-redeem");

    assertEquals(700L, saved.available());
    ArgumentCaptor<PointLedgerEntry> captor = ArgumentCaptor.forClass(PointLedgerEntry.class);
    verify(repository).appendLedger(captor.capture());
    assertEquals("REDEEM", captor.getValue().entryType());
    assertEquals(-300L, captor.getValue().pointsOrZero());
    assertEquals(700L, captor.getValue().balanceAfterOrZero());
    assertEquals("ORDER", captor.getValue().businessType());
    assertEquals(9L, captor.getValue().businessId());
    verify(repository).updateBalanceWithVersion(any(PointAccount.class), eq(STORED_VERSION));
  }

  /** 抵扣余额不足：422 LEDGER_INSUFFICIENT，不改余额、不写流水。 */
  @Test
  void redeemInsufficientIsRejectedWithoutWriting() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(100L)));
    when(repository.ledgerExists("key-short")).thenReturn(false);

    ApiException failure = assertThrows(ApiException.class,
        () -> service.redeem(7L, 300L, 9L, "key-short"));

    assertEquals(422, failure.getStatus());
    assertEquals("LEDGER_INSUFFICIENT", failure.getCode());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 释放已被幂等的反向流水消化过：直接回读账户，不重复加积分、不写第二条流水。 */
  @Test
  void releaseWithExistingLedgerIsIdempotent() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("key-released")).thenReturn(true);

    PointAccount same = service.release(7L, 300L, 9L, "key-released");

    assertEquals(1000L, same.available());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 释放：REVERSE 流水记正数并归还余额。 */
  @Test
  void releaseCreditsBalanceAndAppendsReversedRow() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.ledgerExists("key-release")).thenReturn(false);

    PointAccount saved = service.release(7L, 300L, 9L, "key-release");

    assertEquals(1300L, saved.available());
    ArgumentCaptor<PointLedgerEntry> captor = ArgumentCaptor.forClass(PointLedgerEntry.class);
    verify(repository).appendLedger(captor.capture());
    assertEquals("REVERSE", captor.getValue().entryType());
    assertEquals(300L, captor.getValue().pointsOrZero());
  }

  /** 余额查询：账户不存在是 404（与钱包的零额只读视图不同，积分账户由 C 端入口显式建出）。 */
  @Test
  void balanceWithoutAccountIsNotFound() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());

    ApiException failure = assertThrows(ApiException.class, () -> service.balance(7L));

    assertEquals(404, failure.getStatus());
    assertEquals("POINT_ACCOUNT_NOT_FOUND", failure.getCode());
    verify(repository, never()).insert(any(PointAccount.class));
  }

  /** 确保账户已存在：直接复用，不再插一行（唯一键 uk_cst_point_account_scope 的口径）。 */
  @Test
  void ensureAccountReusesExistingRow() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));

    PointAccount existing = service.ensureAccount(7L);

    assertEquals(11L, existing.id());
    verify(repository, never()).insert(any(PointAccount.class));
  }

  /** 缺失 customerId 一律 400，不落库。 */
  @Test
  void ensureAccountRequiresCustomerId() {
    ApiException failure = assertThrows(ApiException.class, () -> service.ensureAccount(null));

    assertEquals("CUSTOMER_ID_REQUIRED", failure.getCode());
    verify(repository, never()).insert(any(PointAccount.class));
  }

  /** 获得积分：DOWN 取整、写规则快照、按 expiryDays 推导 expiresAt。 */
  @Test
  void earnRoundsDownAndWritesRuleSnapshotWithExpiry() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(0L)));
    when(repository.ledgerExists("earn-key")).thenReturn(false);

    PointAccount saved = service.earn(44L, 7L, 101L, 9L, "earn-key");

    // 倍率缺省 1，101 × 1 向下取整 = 101 分。
    assertEquals(101L, saved.available());
    ArgumentCaptor<PointLedgerEntry> captor = ArgumentCaptor.forClass(PointLedgerEntry.class);
    verify(repository).appendLedger(captor.capture());
    PointLedgerEntry row = captor.getValue();
    assertEquals("EARN", row.entryType());
    assertEquals(101L, row.pointsOrZero());
    assertTrue(row.ruleSnapshotJson().contains("\"earnRate\""), "EARN 必须固化规则快照");
    // 缺省规则 expiryDays=0 → 不失效。
    assertEquals(null, row.expiresAt());
  }

  /** 获得积分为 0（金额太小）：不写流水、不改余额，但账户已确保存在。 */
  @Test
  void earnWithZeroPointsWritesNothing() {
    PointRuleConfigApplicationService ruleService = mock(PointRuleConfigApplicationService.class);
    when(ruleService.resolve(any(), any(), any())).thenReturn(
        new PointRuleConfigApplicationService.PointRule(new java.math.BigDecimal("0.01"),
            java.math.BigDecimal.ONE, 0, 0L, 0, 1L, "", 0L));
    PointApplicationService tiny = new PointApplicationService(repository, auditClient,
        CustomerEventOutbox.disabled(), ruleService);
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(5L)));
    when(repository.ledgerExists("earn-tiny")).thenReturn(false);

    PointAccount unchanged = tiny.earn(44L, 7L, 1L, 9L, "earn-tiny");

    assertEquals(5L, unchanged.available());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 获得积分金额非正数：400 EARN_AMOUNT_INVALID。 */
  @Test
  void earnRejectsNonPositiveAmount() {
    ApiException failure = assertThrows(ApiException.class,
        () -> service.earn(44L, 7L, 0L, 9L, "earn-bad"));

    assertEquals(400, failure.getStatus());
    assertEquals("EARN_AMOUNT_INVALID", failure.getCode());
  }

  /** 获得积分幂等：幂等键已存在 → 直接回读账户并补齐（保证账户存在），不重复加积分。 */
  @Test
  void earnWithExistingLedgerIsIdempotent() {
    when(repository.ledgerExists("earn-dup")).thenReturn(true);
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(500L)));

    PointAccount same = service.earn(44L, 7L, 100L, 9L, "earn-dup");

    assertEquals(500L, same.available());
    verify(repository, never()).appendLedger(any(PointLedgerEntry.class));
    verify(repository, never()).updateBalanceWithVersion(any(), anyInt());
  }

  /** 抵扣金额 → 积分数：向上取整（CEILING），避免少扣。 */
  @Test
  void pointsForAmountRoundsUp() {
    // 缺省 redeemRate = 1 → 150 金额 = 150 分。
    assertEquals(150L, service.pointsForAmount(null, 150L));
  }

  /** 视图：账户不存在时 404，不落库。 */
  @Test
  void viewWithoutAccountIsNotFound() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.empty());

    ApiException failure = assertThrows(ApiException.class, () -> service.view(7L, 1L, 20L));

    assertEquals("POINT_ACCOUNT_NOT_FOUND", failure.getCode());
    verify(repository, never()).listLedger(any(), any(), any(Long.class), any(Long.class));
  }

  /** 视图：分页翻译成「偏移 + 上限」并原样带回请求参数。 */
  @Test
  void viewTranslatesPageToOffsetAndLimit() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.listLedger(eq(11L), isNull(), eq(40L), eq(20L)))
        .thenReturn(new PointAccountRepository.LedgerPage(java.util.List.of(), 0L));

    PointApplicationService.MemberPointsData data = service.view(7L, 3L, 20L);

    assertEquals(11L, data.account().id());
    assertEquals(3L, data.page());
    assertEquals(20L, data.pageSize());
    verify(repository, times(1)).listLedger(11L, null, 40L, 20L);
  }

  /** 非法分页参数回落到第一页，不把负偏移传给端口。 */
  @Test
  void viewFallsBackToFirstPageOnInvalidPageParams() {
    when(repository.findByCustomer(7L)).thenReturn(Optional.of(account(1000L)));
    when(repository.listLedger(any(), any(), any(Long.class), any(Long.class)))
        .thenReturn(new PointAccountRepository.LedgerPage(java.util.List.of(), 0L));

    PointApplicationService.MemberPointsData data = service.view(7L, 0L, 0L);

    assertEquals(1L, data.page());
    assertEquals(1L, data.pageSize());
    verify(repository).listLedger(11L, null, 0L, 1L);
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  /** 库存账户 fixture：版本 {@link #STORED_VERSION}，让 CAS 断言有确定的期望版本。 */
  private static PointAccount account(long availablePoints) {
    return new PointAccount(11L, 100L, 7L, 1L, availablePoints, 0L, STORED_VERSION);
  }
}
