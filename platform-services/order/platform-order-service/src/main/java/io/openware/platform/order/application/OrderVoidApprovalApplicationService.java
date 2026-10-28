package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import io.openware.platform.order.domain.order.model.TradeStatus;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.OrderVoidApprovalRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order 域订单作废审批状态机。申请不改变订单；批准后复用现有作废执行路径。
 *
 * <p><b>返回记账快照的原因</b>：审批响应（{@code /business/orders/void-approvals/**}）带
 * {@code createdAt}/{@code updatedAt}，而审计列不进领域模型（standards/10），
 * 因此端口把「业务聚合 + 记账快照」一起交回，由控制器装配视图。
 *
 * <p><b>并发</b>：「批准/驳回」的判定点在适配器那条带
 * {@code AND version = ? AND status = 'PENDING'} 的条件 UPDATE 上；受影响 0 行 → 409
 * {@code APPROVAL_CONFLICT}。应用层读出来比对只是快速失败提示。
 */
@Service
public class OrderVoidApprovalApplicationService {
    public static final String PENDING = OrderVoidApproval.STATUS_PENDING;
    public static final String APPROVED = OrderVoidApproval.STATUS_APPROVED;
    public static final String REJECTED = OrderVoidApproval.STATUS_REJECTED;
    public static final String EXECUTED = OrderVoidApproval.STATUS_EXECUTED;

    private final OrderVoidApprovalRepository repository;
    private final OrderCancellationApplicationService cancellation;
    private final AuditClient audit;

    public OrderVoidApprovalApplicationService(OrderVoidApprovalRepository repository,
            OrderCancellationApplicationService cancellation, AuditClient audit) {
        this.repository = repository; this.cancellation = cancellation; this.audit = audit;
    }

    @Transactional
    public Persisted<OrderVoidApproval> submit(Long tenantId, Long storeId, Long applicantId, Long orderId,
                                               String reason, String idempotencyKey) {
        if (reason == null || reason.isBlank()) throw new BusinessException("VOID_REASON_REQUIRED", "作废审批必须填写原因");
        if (reason.length() > 512) throw new BusinessException("VOID_REASON_TOO_LONG", "作废原因不能超过512个字符");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // 幂等：同租户同幂等键已申请过则原样返回既有申请（重试语义）。
            var old = repository.findByIdempotencyKey(tenantId, idempotencyKey);
            if (old.isPresent()) return old.get();
        }
        Order order = cancellation.requireOrder(orderId);
        if (!tenantId.equals(order.tenantId()) || !storeId.equals(order.storeId()))
            throw new ApiException(403, "STORE_SCOPE_DENIED", "无权操作该门店订单");
        if (order.trade() == TradeStatus.VOIDED) throw new ApiException(409, "ORDER_STATUS_INVALID", "订单已作废");
        String snapshotJson = "{\"orderNo\":\"" + esc(order.orderNo()) + "\"}";
        OrderVoidApproval application = new OrderVoidApproval(null, tenantId, storeId, order.businessType(),
                orderId, order.currentVersion(), order.tradeStatus(), snapshotJson, reason,
                OrderVoidApproval.STATUS_PENDING, applicantId, null, null, idempotencyKey, 0, null);
        Persisted<OrderVoidApproval> saved = repository.insert(application);
        audit.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(storeId)
                .operatorId(applicantId)
                .action("order.void.request").actionLabel("申请订单作废").resourceType("ord_order")
                .resourceId(String.valueOf(orderId)).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(idempotencyKey).detailJson(snapshotJson).build());
        return saved;
    }

    @Transactional
    public Persisted<OrderVoidApproval> approve(Long tenantId, Long storeId, Long approverId, Long id,
                                                String comment) {
        Persisted<OrderVoidApproval> row = require(tenantId, storeId, id);
        OrderVoidApproval application = row.aggregate();
        if (approverId != null && approverId.equals(application.applicantId()))
            throw new ApiException(403, "APPROVAL_SEPARATION_REQUIRED", "申请人不能审批自己的作废申请");
        if (application.rejected() || application.executed()) return row;
        if (application.approved()) return executeIfNeeded(row);
        if (!application.pending()) throw new ApiException(409, "APPROVAL_STATUS_INVALID", "审批状态不允许通过");
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：只有仍处于 PENDING 且版本相符才算审批成功（判定点在 SQL 上，不在这句比对里）。
        Persisted<OrderVoidApproval> approved = repository.reviewWithVersion(id, tenantId,
                        application.currentVersion(), OrderVoidApproval.STATUS_APPROVED, approverId, comment, now)
                .orElseThrow(() -> new ApiException(409, "APPROVAL_CONFLICT", "审批状态已被其他操作修改"));
        return executeIfNeeded(approved);
    }

    @Transactional
    public Persisted<OrderVoidApproval> reject(Long tenantId, Long storeId, Long approverId, Long id,
                                               String comment) {
        Persisted<OrderVoidApproval> row = require(tenantId, storeId, id);
        OrderVoidApproval application = row.aggregate();
        if (approverId != null && approverId.equals(application.applicantId()))
            throw new ApiException(403, "APPROVAL_SEPARATION_REQUIRED", "申请人不能审批自己的作废申请");
        if (application.rejected()) return row;
        if (!application.pending()) throw new ApiException(409, "APPROVAL_STATUS_INVALID", "审批状态不允许驳回");
        LocalDateTime now = LocalDateTime.now();
        return repository.reviewWithVersion(id, tenantId, application.currentVersion(),
                        OrderVoidApproval.STATUS_REJECTED, approverId, comment, now)
                .orElseThrow(() -> new ApiException(409, "APPROVAL_CONFLICT", "审批状态已被其他操作修改"));
    }

    public Persisted<OrderVoidApproval> get(Long tenantId, Long storeId, Long id) {
        return require(tenantId, storeId, id);
    }

    public List<Persisted<OrderVoidApproval>> list(Long tenantId, Long storeId, String status) {
        return repository.list(tenantId, storeId, status);
    }

    private Persisted<OrderVoidApproval> require(Long tenantId, Long storeId, Long id) {
        return repository.findScoped(tenantId, storeId, id)
                .orElseThrow(() -> new BusinessException("VOID_APPROVAL_NOT_FOUND", "作废审批不存在"));
    }

    /**
     * 审批通过后执行订单作废，并把申请标记为已执行。
     *
     * <p>审批执行重试可能已经完成订单写入；识别已作废状态后只补齐审批状态，避免重复释放资源。
     * 与改造前一致：{@code markExecuted} 的受影响行数**不参与判定**（重复执行本就无害），
     * 响应状态直接标记为已执行、{@code updatedAt} 取本次时刻，版本保持读到的值不变。
     */
    private Persisted<OrderVoidApproval> executeIfNeeded(Persisted<OrderVoidApproval> row) {
        OrderVoidApproval application = row.aggregate();
        if (application.executed()) return row;
        Order order = cancellation.requireOrder(application.orderId());
        if (order.trade() != TradeStatus.VOIDED) {
            cancellation.voidOrderApproved(application.orderId(), application.reason());
        }
        LocalDateTime now = LocalDateTime.now();
        repository.markExecuted(application.id(), application.currentVersion(), now);
        AuditSnapshot previous = row.audit();
        return new Persisted<>(application.executed(OrderVoidApproval.STATUS_EXECUTED),
                new AuditSnapshot(previous.createdBy(), previous.createdAt(), previous.updatedBy(), now));
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
