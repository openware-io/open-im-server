package com.gvchat.platform.tenant.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.platform.tenant.domain.approval.IamApproval;
import com.gvchat.platform.tenant.domain.approval.IamApprovalStatus;
import com.gvchat.platform.tenant.infra.persistence.mapper.IamApprovalMapper;
import com.gvchat.platform.tenant.infra.persistence.po.IamApprovalPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * IAM 高风险动作在线复核应用服务：提交（幂等）、审批通过/拒绝（状态机）、查询。
 *
 * <p>真实实现：审批通过后触发对应高风险动作的后续执行 + 通知复核人；当前骨架不接真实通知，
 * 仅完成接口、状态机、幂等与审计占位。
 */
@Service
public class IamApprovalApplicationService {

    private final IamApprovalMapper mapper;
    private final AuditClient auditClient;

    public IamApprovalApplicationService(IamApprovalMapper mapper, AuditClient auditClient) {
        this.mapper = mapper;
        this.auditClient = auditClient;
    }

    /** 提交高风险动作复核（幂等：同 idempotencyKey 返回既有记录，不重复落库）。 */
    @Transactional
    public IamApproval submit(IamApprovalSubmitCommand command) {
        Objects.requireNonNull(command.tenantId(), "tenantId 必填");
        Objects.requireNonNull(command.actionType(), "actionType 必填");
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            IamApprovalPo existing = mapper.selectOne(new QueryWrapper<IamApprovalPo>()
                    .eq("idempotency_key", command.idempotencyKey()));
            if (existing != null) {
                return toDomain(existing);
            }
        }
        IamApproval approval = IamApproval.submit(command.tenantId(), command.actionType(), command.resourceType(),
                command.resourceId(), command.operatorId(), command.detailJson(), command.idempotencyKey(),
                LocalDateTime.now());
        mapper.insert(toPo(approval));
        auditClient.recordAsync(AuditClient.AuditRecord.of(
                command.tenantId(), command.operatorId(), "iam.approval.submit", command.resourceType(),
                command.resourceId(), null, command.idempotencyKey(), command.detailJson()));
        return approval;
    }

    /** 复核通过（幂等：已 APPROVED 直接返回；REJECTED 抛异常）。 */
    @Transactional
    public IamApproval approve(Long id, Long approverId, String comment) {
        return review(id, approverId, comment, true);
    }

    /** 复核拒绝（幂等：已 REJECTED 直接返回；APPROVED 抛异常）。 */
    @Transactional
    public IamApproval reject(Long id, Long approverId, String comment) {
        return review(id, approverId, comment, false);
    }

    private IamApproval review(Long id, Long approverId, String comment, boolean approve) {
        String action = approve ? "iam.approval.approve" : "iam.approval.reject";
        try {
            IamApprovalPo po = mapper.selectById(id);
            if (po == null) {
                throw new IllegalStateException("复核请求不存在: id=" + id);
            }
            IamApproval approval = toDomain(po);
            LocalDateTime now = LocalDateTime.now();
            if (approve) {
                approval.approve(approverId, comment, now);
            } else {
                approval.reject(approverId, comment, now);
            }
            mapper.updateById(toPo(approval));
            auditClient.recordAsync(AuditClient.AuditRecord.of(
                    approval.getTenantId(), approverId, action, approval.getResourceType(),
                    approval.getResourceId(), null, approval.getIdempotencyKey(),
                    "{\"comment\":\"" + (comment == null ? "" : comment) + "\"}"));
            return approval;
        } catch (RuntimeException failure) {
            // 失败出口留痕（复核单不存在 / 状态机非法 / 落库失败）：审计只 WARN，业务异常原样抛出。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action(action)
                    .operatorId(approverId)
                    .resourceType("iam_approval")
                    .resourceId(id == null ? null : String.valueOf(id))
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .idempotencyKey(action + ":failure:" + id + ":" + System.currentTimeMillis())
                    .build());
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public IamApproval get(Long id) {
        IamApprovalPo po = mapper.selectById(id);
        return po == null ? null : toDomain(po);
    }

    @Transactional(readOnly = true)
    public List<IamApproval> list(String actionType, String status) {
        QueryWrapper<IamApprovalPo> qw = new QueryWrapper<>();
        qw.eq(actionType != null && !actionType.isBlank(), "action_type", actionType)
                .eq(status != null && !status.isBlank(), "status", status)
                .orderByDesc("created_at");
        return mapper.selectList(qw).stream().map(IamApprovalApplicationService::toDomain).toList();
    }

    private static IamApproval toDomain(IamApprovalPo po) {
        IamApproval approval = new IamApproval();
        approval.restore(po.getId(), po.getTenantId(), po.getActionType(), po.getResourceType(), po.getResourceId(),
                po.getOperatorId(), po.getDetailJson(), IamApprovalStatus.valueOf(po.getStatus()), po.getIdempotencyKey(),
                po.getApproverId(), po.getReviewComment(), po.getCreatedAt(), po.getUpdatedAt(), po.getReviewedAt());
        return approval;
    }

    private static IamApprovalPo toPo(IamApproval approval) {
        IamApprovalPo po = new IamApprovalPo();
        po.setId(approval.getId());
        po.setTenantId(approval.getTenantId());
        po.setActionType(approval.getActionType());
        po.setResourceType(approval.getResourceType());
        po.setResourceId(approval.getResourceId());
        po.setOperatorId(approval.getOperatorId());
        po.setDetailJson(approval.getDetailJson());
        po.setStatus(approval.getStatus().name());
        po.setIdempotencyKey(approval.getIdempotencyKey());
        po.setApproverId(approval.getApproverId());
        po.setReviewComment(approval.getReviewComment());
        po.setCreatedAt(approval.getCreatedAt());
        po.setUpdatedAt(approval.getUpdatedAt());
        po.setReviewedAt(approval.getReviewedAt());
        return po;
    }

    public record IamApprovalSubmitCommand(Long tenantId, String actionType, String resourceType, String resourceId,
                                           Long operatorId, String detailJson, String idempotencyKey) {
    }
}
