package io.openware.platform.tenant.domain.approval;

/**
 * IAM 高风险动作在线复核状态机：PENDING → APPROVED / REJECTED（终态）。
 */
public enum IamApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
