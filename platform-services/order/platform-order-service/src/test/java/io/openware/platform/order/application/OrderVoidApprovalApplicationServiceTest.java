package io.openware.platform.order.application;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.OrderVoidApprovalRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作废审批状态机的端口化装配：
 * 「按范围取申请」→「带版本+状态的 CAS 审批」→「执行订单作废并标记 EXECUTED」。
 *
 * <p>并发判定点（{@code WHERE version = ? AND status = 'PENDING'}）在适配器里，
 * 应用层只把「CAS 返回空」当成 409 冲突；SQL 形状由 {@code OrderVoidApprovalRepositoryAdapterTest} 之外
 * 的适配器测试钉住（本类无法也不应断言 SQL）。
 */
@ExtendWith(MockitoExtension.class)
class OrderVoidApprovalApplicationServiceTest {
    @Mock private OrderVoidApprovalRepository mapper;
    @Mock private OrderCancellationApplicationService cancellation;
    @Mock private AuditClient audit;

    @Test
    void approveQueriesApprovalByTenantStoreAndApprovalIdThenExecutesVoid() {
        OrderVoidApprovalApplicationService service = new OrderVoidApprovalApplicationService(mapper, cancellation, audit);
        OrderVoidApproval approval = approval(91L, 11L, 21L, 31L, 41L);
        when(mapper.findScoped(11L, 21L, 91L)).thenReturn(Optional.of(persisted(approval)));
        // CAS 成功：端口返回落库后的最新行（APPROVED、版本 +1）。
        when(mapper.reviewWithVersion(eq(91L), eq(11L), eq(0), eq(OrderVoidApproval.STATUS_APPROVED),
                eq(51L), eq("同意作废"), any()))
                .thenReturn(Optional.of(persisted(transition(approval, OrderVoidApproval.STATUS_APPROVED, 1))));
        when(mapper.markExecuted(eq(91L), anyInt(), any())).thenReturn(true);
        when(cancellation.requireOrder(31L)).thenReturn(order(31L, "SERVING"));

        Persisted<OrderVoidApproval> result = service.approve(11L, 21L, 51L, 91L, "同意作废");

        assertEquals(OrderVoidApprovalApplicationService.EXECUTED, result.aggregate().status());
        verify(cancellation).voidOrderApproved(31L, "测试作废");
        verify(mapper).findScoped(11L, 21L, 91L);
    }

    @Test
    void rejectDoesNotExecuteOrderVoid() {
        OrderVoidApprovalApplicationService service = new OrderVoidApprovalApplicationService(mapper, cancellation, audit);
        OrderVoidApproval approval = approval(92L, 12L, 22L, 32L, 42L);
        when(mapper.findScoped(12L, 22L, 92L)).thenReturn(Optional.of(persisted(approval)));
        when(mapper.reviewWithVersion(eq(92L), eq(12L), eq(0), eq(OrderVoidApproval.STATUS_REJECTED),
                eq(52L), eq("不同意"), any()))
                .thenReturn(Optional.of(persisted(transition(approval, OrderVoidApproval.STATUS_REJECTED, 1))));

        Persisted<OrderVoidApproval> result = service.reject(12L, 22L, 52L, 92L, "不同意");

        assertEquals(OrderVoidApprovalApplicationService.REJECTED, result.aggregate().status());
        verify(cancellation, never()).voidOrderApproved(any(), any());
    }

    private static OrderVoidApproval approval(long id, long tenantId, long storeId, long orderId, long applicantId) {
        return new OrderVoidApproval(id, tenantId, storeId, "KTV", orderId, 0, "SERVING", null, "测试作废",
                OrderVoidApproval.STATUS_PENDING, applicantId, null, null, null, 0, null);
    }

    /** 状态/版本流转后的新实例（领域模型不可变）。 */
    private static OrderVoidApproval transition(OrderVoidApproval approval, String status, int version) {
        return new OrderVoidApproval(approval.id(), approval.tenantId(), approval.storeId(),
                approval.businessType(), approval.orderId(), approval.orderVersion(),
                approval.orderStatusSnapshot(), approval.orderSnapshotJson(), approval.reason(), status,
                approval.applicantId(), approval.approverId(), approval.reviewComment(),
                approval.idempotencyKey(), version, approval.reviewedAt());
    }

    /** 订单夹具（执行作废时读它判断是否已经是 VOIDED）。 */
    private static Order order(long id, String status) {
        return new Order(id, 11L, null, 21L, "ORD-20260918-0001", null, "KTV", null, status, "CNY",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null, 0);
    }

    private static Persisted<OrderVoidApproval> persisted(OrderVoidApproval approval) {
        return new Persisted<>(approval, new AuditSnapshot(null, LocalDateTime.now(), null, LocalDateTime.now()));
    }
}
