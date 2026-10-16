package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.tenant.application.IamApprovalApplicationService.IamApprovalSubmitCommand;
import io.openware.platform.tenant.domain.approval.IamApproval;
import io.openware.platform.tenant.domain.approval.IamApprovalActionType;
import io.openware.platform.tenant.domain.approval.IamApprovalStatus;
import io.openware.platform.tenant.domain.approval.repository.IamApprovalRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IAM 高风险动作复核：提交幂等、状态机、失败留痕。
 *
 * <p>替身是**领域端口**：测试不再构造持久化对象，也不再依赖 mapper 的查询形状
 * （standards/10“测试规则”）。落库由适配器负责，本文件断言服务交给端口的聚合状态。
 */
class IamApprovalApplicationServiceTest {

  private final IamApprovalRepository approvalRepository = mock(IamApprovalRepository.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final IamApprovalApplicationService service =
      new IamApprovalApplicationService(approvalRepository, auditClient);

  /** 端口保存返回落库后的聚合（真实适配器回填主键，测试替身等价模拟）。 */
  private void savingReturnsArgument() {
    when(approvalRepository.save(any(IamApproval.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void submit_insertsPendingApproval() {
    savingReturnsArgument();
    when(approvalRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

    IamApproval result = service.submit(command("key-1"));

    assertEquals(IamApprovalStatus.PENDING, result.getStatus());
    ArgumentCaptor<IamApproval> captor = ArgumentCaptor.forClass(IamApproval.class);
    verify(approvalRepository).save(captor.capture());
    assertEquals(IamApprovalStatus.PENDING, captor.getValue().getStatus());
    assertEquals("key-1", captor.getValue().getIdempotencyKey());
  }

  @Test
  void submit_isIdempotentByKey() {
    IamApproval existing = approval(9L);
    when(approvalRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

    IamApproval result = service.submit(command("key-1"));

    assertEquals(9L, result.getId());
    verify(approvalRepository, never()).save(any(IamApproval.class));
  }

  @Test
  void approve_persistsApprovedStatus() {
    savingReturnsArgument();
    when(approvalRepository.findById(1L)).thenReturn(Optional.of(approval(1L)));

    IamApproval result = service.approve(1L, 7L, "ok");

    assertEquals(IamApprovalStatus.APPROVED, result.getStatus());
    ArgumentCaptor<IamApproval> captor = ArgumentCaptor.forClass(IamApproval.class);
    verify(approvalRepository).save(captor.capture());
    assertEquals(IamApprovalStatus.APPROVED, captor.getValue().getStatus());
    assertEquals(7L, captor.getValue().getApproverId());
  }

  @Test
  void reject_persistsRejectedStatus() {
    savingReturnsArgument();
    when(approvalRepository.findById(1L)).thenReturn(Optional.of(approval(1L)));

    IamApproval result = service.reject(1L, 7L, "denied");

    assertEquals(IamApprovalStatus.REJECTED, result.getStatus());
  }

  @Test
  void review_throwsWhenNotFound() {
    when(approvalRepository.findById(1L)).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> service.approve(1L, 7L, "ok"));
  }

  /** ③ 失败留痕：复核失败除抛异常外必须落一条 FAILURE，并带可检索的 errorCode 与操作人。 */
  @Test
  void review_recordsFailureAuditWhenApprovalMissing() {
    when(approvalRepository.findById(1L)).thenReturn(Optional.empty());

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
    when(approvalRepository.findById(2L)).thenReturn(Optional.empty());

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

  /** 待复核夹具：走聚合的提交工厂（与生产路径同一构造方式），再回填主键模拟"已落库"。 */
  private static IamApproval approval(Long id) {
    IamApproval approval = IamApproval.submit(1L, IamApprovalActionType.ROLE_ASSIGN, "role", "42", 9L,
        "{\"roleId\":42}", "key-1", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
    if (id != null) {
      approval.restore(id, 1L, IamApprovalActionType.ROLE_ASSIGN, "role", "42", 9L, "{\"roleId\":42}",
          IamApprovalStatus.PENDING, "key-1", null, null,
          LocalDateTime.of(2026, 1, 2, 3, 4, 5), LocalDateTime.of(2026, 1, 2, 3, 4, 5), null);
    }
    return approval;
  }
}
