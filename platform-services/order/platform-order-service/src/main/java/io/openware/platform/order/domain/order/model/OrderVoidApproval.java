package io.openware.platform.order.domain.order.model;

import java.time.LocalDateTime;

/**
 * 订单作废审批（领域模型）。
 *
 * <p>{@code ord_order_void_approval} 的真实列：id / tenant_id / store_id / business_type / order_id /
 * order_version / order_status_snapshot / order_snapshot_json / reason / status / applicant_id /
 * approver_id / review_comment / idempotency_key / version / reviewed_at
 * + created_at / updated_at（后两列是记账信息，不进领域模型，见 {@code AuditSnapshot}）。
 *
 * <p>{@code reviewed_at} 与 {@code order_version}/{@code order_status_snapshot} 一样是**业务列**（审批发生时刻、
 * 申请时的订单版本与状态快照），因此留在模型上；{@code created_at}/{@code updated_at} 只在响应装配时需要。
 *
 * <p>状态机：{@code PENDING → APPROVED → EXECUTED}、{@code PENDING → REJECTED}。
 * 「执行」不是审批动作本身，而是审批通过后**真正把订单作废**的结果标记（见
 * {@code OrderVoidApprovalApplicationService#executeIfNeeded}），因此 {@link #status} 与订单状态是两件事。
 *
 * @param id                  审批标识
 * @param tenantId            租户
 * @param storeId             门店
 * @param businessType        业态（申请时的订单快照）
 * @param orderId             被申请作废的订单
 * @param orderVersion        申请时的订单版本（审批执行时用于说明「审的是哪一版」）
 * @param orderStatusSnapshot 申请时的订单状态
 * @param orderSnapshotJson   申请时的订单快照 JSON（审计详情复用）
 * @param reason              作废原因（必填，上限 512）
 * @param status              PENDING / APPROVED / REJECTED / EXECUTED
 * @param applicantId         申请人
 * @param approverId          审批人
 * @param reviewComment       审批意见
 * @param idempotencyKey      幂等键（可空；非空时同租户内唯一）
 * @param version             乐观锁版本号
 * @param reviewedAt          审批时刻（APPROVED/REJECTED/EXECUTED 落库）
 */
public record OrderVoidApproval(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long orderId,
    Integer orderVersion,
    String orderStatusSnapshot,
    String orderSnapshotJson,
    String reason,
    String status,
    Long applicantId,
    Long approverId,
    String reviewComment,
    String idempotencyKey,
    Integer version,
    LocalDateTime reviewedAt) {

    /** 待审批。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 审批通过（尚未执行订单作废）。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 审批驳回。 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 审批通过且订单作废已执行（终态）。 */
    public static final String STATUS_EXECUTED = "EXECUTED";

    /** 是否待审批。 */
    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

    /** 是否已审批通过（含已执行）。 */
    public boolean approved() {
        return STATUS_APPROVED.equals(status);
    }

    /** 是否已驳回。 */
    public boolean rejected() {
        return STATUS_REJECTED.equals(status);
    }

    /** 是否已执行完成（终态）。 */
    public boolean executed() {
        return STATUS_EXECUTED.equals(status);
    }

    /** 版本号；为空视为 0（历史数据可能没有版本）。 */
    public int currentVersion() {
        return version == null ? 0 : version;
    }

    /** 同一申请、但状态标记为「已执行」的新实例（订单作废动作已完成）。 */
    public OrderVoidApproval executed(String newStatus) {
        return new OrderVoidApproval(id, tenantId, storeId, businessType, orderId, orderVersion,
            orderStatusSnapshot, orderSnapshotJson, reason, newStatus, applicantId, approverId, reviewComment,
            idempotencyKey, version, reviewedAt);
    }
}
