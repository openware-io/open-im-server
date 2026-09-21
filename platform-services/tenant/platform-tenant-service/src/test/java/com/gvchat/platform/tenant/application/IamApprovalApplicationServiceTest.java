package com.gvchat.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.tenant.application.IamApprovalApplicationService.IamApprovalSubmitCommand;
import com.gvchat.platform.tenant.domain.approval.IamApproval;
import com.gvchat.platform.tenant.domain.approval.IamApprovalStatus;
import com.gvchat.platform.tenant.infra.persistence.mapper.IamApprovalMapper;
import com.gvchat.platform.tenant.infra.persistence.po.IamApprovalPo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** IAM 在线复核应用服务：提交幂等、审批/拒绝落库、不存在抛异常。 */
class IamApprovalApplicationServiceTest {

  private final IamApprovalMapper mapper = mock(IamApprovalMapper.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final IamApprovalApplicationService service = new IamApprovalApplicationService(mapper, auditClient);

  @Test
  void submit_insertsPendingApproval() {
    when(mapper.selectOne(any())).thenReturn(null);
    when(mapper.insert(any(IamApprovalPo.class))).thenReturn(1);

    IamApproval result = service.submit(command("key-1"));

    assertEquals(IamApprovalStatus.PENDING, result.getStatus());
    ArgumentCaptor<IamApprovalPo> captor = ArgumentCaptor.forClass(IamApprovalPo.class);
    verify(mapper).insert(captor.capture());
    assertEquals("PENDING", captor.getValue().getStatus());
    assertEquals("key-1", captor.getValue().getIdempotencyKey());
  }

  @Test
  void submit_isIdempotentByKey() {
    IamApprovalPo existing = po();
    existing.setId(9L);
    when(mapper.selectOne(any())).thenReturn(existing);

    IamApproval result = service.submit(command("key-1"));

    assertEquals(9L, result.getId());
    verify(mapper, never()).insert(any(IamApprovalPo.class));
  }

  @Test
  void approve_persistsApprovedStatus() {
    when(mapper.selectById(1L)).thenReturn(po());
    when(mapper.updateById(any(IamApprovalPo.class))).thenReturn(1);

    IamApproval result = service.approve(1L, 7L, "ok");

    assertEquals(IamApprovalStatus.APPROVED, result.getStatus());
    ArgumentCaptor<IamApprovalPo> captor = ArgumentCaptor.forClass(IamApprovalPo.class);
    verify(mapper).updateById(captor.capture());
    assertEquals("APPROVED", captor.getValue().getStatus());
    assertEquals(7L, captor.getValue().getApproverId());
  }

  @Test
  void reject_persistsRejectedStatus() {
    when(mapper.selectById(1L)).thenReturn(po());
    when(mapper.updateById(any(IamApprovalPo.class))).thenReturn(1);

    IamApproval result = service.reject(1L, 7L, "denied");

    assertEquals(IamApprovalStatus.REJECTED, result.getStatus());
  }

  @Test
  void review_throwsWhenNotFound() {
    when(mapper.selectById(1L)).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> service.approve(1L, 7L, "ok"));
  }

  /** ③ 失败留痕：复核失败除抛异常外必须落一条 FAILURE，并带可检索的 errorCode 与操作人。 */
  @Test
  void review_recordsFailureAuditWhenApprovalMissing() {
    when(mapper.selectById(1L)).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> service.approve(1L, 7L, "ok"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("iam.approval.approve", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertEquals(7L, record.operatorId());
    assertEquals("1", record.resourceId());
  }

  /** ③ 失败留痕：驳回路径失败同样留痕（动作码必须是 reject，不能与 approve 混用）。 */
  @Test
  void reviewFailureKeepsRejectActionCode() {
    when(mapper.selectById(2L)).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> service.reject(2L, 7L, "denied"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("iam.approval.reject", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private static IamApprovalSubmitCommand command(String key) {
    return new IamApprovalSubmitCommand(1L, "ROLE_ASSIGN", "role", "42", 9L, "{\"roleId\":42}", key);
  }

  private static IamApprovalPo po() {
    IamApprovalPo po = new IamApprovalPo();
    po.setId(1L);
    po.setTenantId(1L);
    po.setActionType("ROLE_ASSIGN");
    po.setResourceType("role");
    po.setResourceId("42");
    po.setOperatorId(9L);
    po.setDetailJson("{\"roleId\":42}");
    po.setStatus("PENDING");
    po.setIdempotencyKey("key-1");
    po.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
    po.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
    return po;
  }
}
