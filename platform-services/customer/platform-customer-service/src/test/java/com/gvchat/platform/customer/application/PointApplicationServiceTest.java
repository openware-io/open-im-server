package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.customer.infra.persistence.mapper.PointAccountMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstPointAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstPointLedgerPo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 积分账户写操作的失败留痕回归（③ 遗留）：领域服务内部的失败出口此前没有审计记录，
 * 只有 BFF 路径由拦截器兜底。这里断言「业务异常照旧抛出 + 同时落一条 FAILURE 且带稳定错误码」。
 */
class PointApplicationServiceTest {

  private final PointAccountMapper accountMapper = mock(PointAccountMapper.class);
  private final PointLedgerMapper ledgerMapper = mock(PointLedgerMapper.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final PointApplicationService service =
      new PointApplicationService(accountMapper, ledgerMapper, auditClient);

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
    when(accountMapper.selectOne(any())).thenReturn(account(10L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    assertThrows(ApiException.class, () -> service.adjust(7L, -50L, "扣减", "cmd-2"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
    // 失败留痕不得改动账本：异常抛出前不允许落流水。
    verify(ledgerMapper, org.mockito.Mockito.never()).insert(any(CstPointLedgerPo.class));
  }

  /** 成功路径只留成功记录（不得混入 FAILURE）。 */
  @Test
  void adjustSuccessRecordsSuccessAudit() {
    when(accountMapper.selectOne(any())).thenReturn(account(1000L));
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    service.adjust(7L, 100L, "补偿", "cmd-3");

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("points.adjust", record.action());
    // 成功路径不显式标记结果，由 SDK buildBody 缺省为 SUCCESS；关键是不能被标成 FAILURE。
    assertNotEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("cmd-3", record.idempotencyKey());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static CstPointAccountPo account(long availablePoints) {
    CstPointAccountPo po = new CstPointAccountPo();
    po.setId(11L);
    po.setCustomerId(7L);
    po.setAvailablePoints(availablePoints);
    po.setFrozenPoints(0L);
    po.setVersion(0);
    return po;
  }
}
