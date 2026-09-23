package io.openware.platform.tenant.domain.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** IAM 在线复核状态机：PENDING → APPROVED/REJECTED，同向终态幂等，异向终态抛异常。 */
class IamApprovalTest {

  @Test
  void submit_createsPendingApproval() {
    IamApproval approval = submit();

    assertEquals(IamApprovalStatus.PENDING, approval.getStatus());
    assertEquals(IamApprovalActionType.ROLE_ASSIGN, approval.getActionType());
    assertNull(approval.getApproverId());
    assertNull(approval.getReviewedAt());
  }

  @Test
  void approve_transitionsPendingToApproved() {
    IamApproval approval = submit();

    approval.approve(7L, "ok", now());

    assertEquals(IamApprovalStatus.APPROVED, approval.getStatus());
    assertEquals(7L, approval.getApproverId());
    assertEquals("ok", approval.getReviewComment());
  }

  @Test
  void reject_transitionsPendingToRejected() {
    IamApproval approval = submit();

    approval.reject(7L, "denied", now());

    assertEquals(IamApprovalStatus.REJECTED, approval.getStatus());
  }

  @Test
  void approve_isIdempotentOnAlreadyApproved() {
    IamApproval approval = submit();
    approval.approve(7L, "ok", now());

    approval.approve(8L, "again", now());

    assertEquals(IamApprovalStatus.APPROVED, approval.getStatus());
    assertEquals(7L, approval.getApproverId());
  }

  @Test
  void reject_isIdempotentOnAlreadyRejected() {
    IamApproval approval = submit();
    approval.reject(7L, "no", now());

    approval.reject(8L, "again", now());

    assertEquals(IamApprovalStatus.REJECTED, approval.getStatus());
    assertEquals(7L, approval.getApproverId());
  }

  @Test
  void approve_afterRejectThrows() {
    IamApproval approval = submit();
    approval.reject(7L, "no", now());

    assertThrows(IllegalStateException.class, () -> approval.approve(8L, "late", now()));
  }

  @Test
  void reject_afterApproveThrows() {
    IamApproval approval = submit();
    approval.approve(7L, "ok", now());

    assertThrows(IllegalStateException.class, () -> approval.reject(8L, "late", now()));
  }

  private static IamApproval submit() {
    return IamApproval.submit(1L, IamApprovalActionType.ROLE_ASSIGN, "role", "42", 9L,
        "{\"roleId\":42}", "key-1", now());
  }

  private static LocalDateTime now() {
    return LocalDateTime.of(2026, 1, 2, 3, 4, 5);
  }
}
