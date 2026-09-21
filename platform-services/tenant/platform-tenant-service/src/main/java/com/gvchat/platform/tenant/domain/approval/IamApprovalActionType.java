package com.gvchat.platform.tenant.domain.approval;

/**
 * IAM 高风险动作类型常量：角色/权限变更、退款审批、密钥操作。
 * 采用 String 而非枚举，便于跨服务（订单退款、密钥中心）扩展新动作类型而不改本模块。
 */
public final class IamApprovalActionType {

    public static final String ROLE_ASSIGN = "ROLE_ASSIGN";
    public static final String ROLE_PERMISSION_ASSIGN = "ROLE_PERMISSION_ASSIGN";
    public static final String REFUND_APPROVAL = "REFUND_APPROVAL";
    public static final String KEY_OPERATION = "KEY_OPERATION";

    private IamApprovalActionType() {
    }
}
