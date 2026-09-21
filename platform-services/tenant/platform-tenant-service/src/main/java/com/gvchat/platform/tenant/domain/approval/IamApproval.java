package com.gvchat.platform.tenant.domain.approval;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * IAM 高风险动作在线复核聚合：记录一次高风险动作的二次复核请求及其审批结论。
 *
 * <p>状态机：PENDING → APPROVED / REJECTED（终态）。approve/reject 对同向终态幂等（无副作用返回），
 * 对异向终态或非法状态抛 {@link IllegalStateException}。
 */
@Getter
public class IamApproval {

    private Long id;
    private Long tenantId;
    private String actionType;
    private String resourceType;
    private String resourceId;
    private Long operatorId;
    private String detailJson;
    private IamApprovalStatus status;
    private String idempotencyKey;
    private Long approverId;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime reviewedAt;

    /** 提交一次高风险动作复核请求，初始状态 PENDING。 */
    public static IamApproval submit(Long tenantId, String actionType, String resourceType, String resourceId,
                                     Long operatorId, String detailJson, String idempotencyKey, LocalDateTime occurredAt) {
        IamApproval approval = new IamApproval();
        approval.tenantId = tenantId;
        approval.actionType = actionType;
        approval.resourceType = resourceType;
        approval.resourceId = resourceId;
        approval.operatorId = operatorId;
        approval.detailJson = detailJson;
        approval.idempotencyKey = idempotencyKey;
        approval.status = IamApprovalStatus.PENDING;
        approval.createdAt = occurredAt;
        approval.updatedAt = occurredAt;
        return approval;
    }

    /** 复核通过：PENDING → APPROVED；已 APPROVED 幂等返回；REJECTED 抛异常。 */
    public void approve(Long approverId, String comment, LocalDateTime reviewedAt) {
        if (status == IamApprovalStatus.APPROVED) {
            return;
        }
        if (status != IamApprovalStatus.PENDING) {
            throw new IllegalStateException("仅 PENDING 状态的复核请求可批准，当前=" + status);
        }
        this.status = IamApprovalStatus.APPROVED;
        this.approverId = approverId;
        this.reviewComment = comment;
        this.reviewedAt = reviewedAt;
        this.updatedAt = reviewedAt;
    }

    /** 复核拒绝：PENDING → REJECTED；已 REJECTED 幂等返回；APPROVED 抛异常。 */
    public void reject(Long approverId, String comment, LocalDateTime reviewedAt) {
        if (status == IamApprovalStatus.REJECTED) {
            return;
        }
        if (status != IamApprovalStatus.PENDING) {
            throw new IllegalStateException("仅 PENDING 状态的复核请求可拒绝，当前=" + status);
        }
        this.status = IamApprovalStatus.REJECTED;
        this.approverId = approverId;
        this.reviewComment = comment;
        this.reviewedAt = reviewedAt;
        this.updatedAt = reviewedAt;
    }

    /** 从持久化态重建（仅持久化适配层调用）。 */
    public void restore(Long id, Long tenantId, String actionType, String resourceType, String resourceId,
                        Long operatorId, String detailJson, IamApprovalStatus status, String idempotencyKey,
                        Long approverId, String reviewComment, LocalDateTime createdAt, LocalDateTime updatedAt,
                        LocalDateTime reviewedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.actionType = actionType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.operatorId = operatorId;
        this.detailJson = detailJson;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.approverId = approverId;
        this.reviewComment = reviewComment;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.reviewedAt = reviewedAt;
    }
}
