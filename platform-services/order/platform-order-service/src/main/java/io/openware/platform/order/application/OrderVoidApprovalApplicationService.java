package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.infra.persistence.mapper.OrdOrderVoidApprovalMapper;
import io.openware.platform.order.infra.persistence.po.OrdOrderVoidApprovalPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Order 域订单作废审批状态机。申请不改变订单；批准后复用现有作废执行路径。 */
@Service
public class OrderVoidApprovalApplicationService {
    public static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED", EXECUTED = "EXECUTED";
    private final OrdOrderVoidApprovalMapper mapper;
    private final OrderCancellationApplicationService cancellation;
    private final AuditClient audit;
    public OrderVoidApprovalApplicationService(OrdOrderVoidApprovalMapper mapper,
            OrderCancellationApplicationService cancellation, AuditClient audit) {
        this.mapper = mapper; this.cancellation = cancellation; this.audit = audit;
    }

    @Transactional
    public OrdOrderVoidApprovalPo submit(Long tenantId, Long storeId, Long applicantId, Long orderId,
                                         String reason, String idempotencyKey) {
        if (reason == null || reason.isBlank()) throw new BusinessException("VOID_REASON_REQUIRED", "作废审批必须填写原因");
        if (reason.length() > 512) throw new BusinessException("VOID_REASON_TOO_LONG", "作废原因不能超过512个字符");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            OrdOrderVoidApprovalPo old = mapper.selectOne(new LambdaQueryWrapper<OrdOrderVoidApprovalPo>()
                    .eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
                    .eq(OrdOrderVoidApprovalPo::getIdempotencyKey, idempotencyKey));
            if (old != null) return old;
        }
        OrderPo order = cancellation.requireOrder(orderId);
        if (!tenantId.equals(order.getTenantId()) || !storeId.equals(order.getStoreId()))
            throw new ApiException(403, "STORE_SCOPE_DENIED", "无权操作该门店订单");
        if ("VOIDED".equals(order.getStatus())) throw new ApiException(409, "ORDER_STATUS_INVALID", "订单已作废");
        LocalDateTime now = LocalDateTime.now();
        OrdOrderVoidApprovalPo p = new OrdOrderVoidApprovalPo();
        p.setTenantId(tenantId); p.setStoreId(storeId); p.setBusinessType(order.getBusinessType());
        p.setOrderId(orderId); p.setOrderVersion(order.getVersion() == null ? 0 : order.getVersion());
        p.setOrderStatusSnapshot(order.getStatus()); p.setOrderSnapshotJson("{\"orderNo\":\"" + esc(order.getOrderNo()) + "\"}");
        p.setReason(reason); p.setStatus(PENDING); p.setApplicantId(applicantId); p.setIdempotencyKey(idempotencyKey);
        p.setVersion(0); p.setCreatedAt(now); p.setUpdatedAt(now); mapper.insert(p);
        audit.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(storeId).operatorId(applicantId)
                .action("order.void.request").actionLabel("申请订单作废").resourceType("ord_order")
                .resourceId(String.valueOf(orderId)).result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(idempotencyKey).detailJson(p.getOrderSnapshotJson()).build());
        return p;
    }

    @Transactional
    public OrdOrderVoidApprovalPo approve(Long tenantId, Long storeId, Long approverId, Long id, String comment) {
        OrdOrderVoidApprovalPo p = require(id, tenantId, storeId);
        if (approverId != null && approverId.equals(p.getApplicantId()))
            throw new ApiException(403, "APPROVAL_SEPARATION_REQUIRED", "申请人不能审批自己的作废申请");
        if (REJECTED.equals(p.getStatus()) || EXECUTED.equals(p.getStatus())) return p;
        if (APPROVED.equals(p.getStatus())) return executeIfNeeded(p, approverId);
        if (!PENDING.equals(p.getStatus())) throw new ApiException(409, "APPROVAL_STATUS_INVALID", "审批状态不允许通过");
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.update(null, new LambdaUpdateWrapper<OrdOrderVoidApprovalPo>()
                .eq(OrdOrderVoidApprovalPo::getId, id).eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
                .eq(OrdOrderVoidApprovalPo::getVersion, p.getVersion()).eq(OrdOrderVoidApprovalPo::getStatus, PENDING)
                .set(OrdOrderVoidApprovalPo::getStatus, APPROVED).set(OrdOrderVoidApprovalPo::getApproverId, approverId)
                .set(OrdOrderVoidApprovalPo::getReviewComment, comment).set(OrdOrderVoidApprovalPo::getReviewedAt, now)
                .set(OrdOrderVoidApprovalPo::getUpdatedAt, now).set(OrdOrderVoidApprovalPo::getVersion, p.getVersion() + 1));
        if (changed == 0) throw new ApiException(409, "APPROVAL_CONFLICT", "审批状态已被其他操作修改");
        p = mapper.selectById(id);
        return executeIfNeeded(p, approverId);
    }

    @Transactional
    public OrdOrderVoidApprovalPo reject(Long tenantId, Long storeId, Long approverId, Long id, String comment) {
        OrdOrderVoidApprovalPo p = require(id, tenantId, storeId);
        if (approverId != null && approverId.equals(p.getApplicantId()))
            throw new ApiException(403, "APPROVAL_SEPARATION_REQUIRED", "申请人不能审批自己的作废申请");
        if (REJECTED.equals(p.getStatus())) return p;
        if (!PENDING.equals(p.getStatus())) throw new ApiException(409, "APPROVAL_STATUS_INVALID", "审批状态不允许驳回");
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.update(null, new LambdaUpdateWrapper<OrdOrderVoidApprovalPo>()
                .eq(OrdOrderVoidApprovalPo::getId, id).eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
                .eq(OrdOrderVoidApprovalPo::getVersion, p.getVersion()).eq(OrdOrderVoidApprovalPo::getStatus, PENDING)
                .set(OrdOrderVoidApprovalPo::getStatus, REJECTED).set(OrdOrderVoidApprovalPo::getApproverId, approverId)
                .set(OrdOrderVoidApprovalPo::getReviewComment, comment).set(OrdOrderVoidApprovalPo::getReviewedAt, now)
                .set(OrdOrderVoidApprovalPo::getUpdatedAt, now).set(OrdOrderVoidApprovalPo::getVersion, p.getVersion() + 1));
        if (changed == 0) throw new ApiException(409, "APPROVAL_CONFLICT", "审批状态已被其他操作修改");
        return mapper.selectById(id);
    }

    public OrdOrderVoidApprovalPo get(Long tenantId, Long storeId, Long id) { return require(id, tenantId, storeId); }
    public List<OrdOrderVoidApprovalPo> list(Long tenantId, Long storeId, String status) {
        return mapper.selectList(new LambdaQueryWrapper<OrdOrderVoidApprovalPo>().eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
                .eq(OrdOrderVoidApprovalPo::getStoreId, storeId).eq(status != null, OrdOrderVoidApprovalPo::getStatus, status)
                .orderByDesc(OrdOrderVoidApprovalPo::getCreatedAt));
    }
    private OrdOrderVoidApprovalPo require(Long tenantId, Long storeId, Long id) {
        OrdOrderVoidApprovalPo p = mapper.selectOne(new LambdaQueryWrapper<OrdOrderVoidApprovalPo>().eq(OrdOrderVoidApprovalPo::getId,id)
                .eq(OrdOrderVoidApprovalPo::getTenantId,tenantId).eq(OrdOrderVoidApprovalPo::getStoreId,storeId));
        if (p == null) throw new BusinessException("VOID_APPROVAL_NOT_FOUND", "作废审批不存在"); return p;
    }
    private OrdOrderVoidApprovalPo executeIfNeeded(OrdOrderVoidApprovalPo p, Long approverId) {
        if (EXECUTED.equals(p.getStatus())) return p;
        // 审批执行重试可能已经完成订单写入；识别已作废状态后只补齐审批状态，避免重复释放资源。
        OrderPo order = cancellation.requireOrder(p.getOrderId());
        if (!"VOIDED".equals(order.getStatus())) {
            cancellation.voidOrder(p.getOrderId(), p.getReason());
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new LambdaUpdateWrapper<OrdOrderVoidApprovalPo>().eq(OrdOrderVoidApprovalPo::getId,p.getId())
                .eq(OrdOrderVoidApprovalPo::getStatus, APPROVED).set(OrdOrderVoidApprovalPo::getStatus,EXECUTED)
                .set(OrdOrderVoidApprovalPo::getUpdatedAt,now).set(OrdOrderVoidApprovalPo::getVersion,p.getVersion()+1));
        p.setStatus(EXECUTED); p.setUpdatedAt(now);
        return p;
    }
    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
