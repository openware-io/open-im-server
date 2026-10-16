package io.openware.platform.order.domain.order.model;

/**
 * 作废审批规则（领域模型）：租户/业态默认或门店覆盖。
 *
 * <p>{@code ord_void_rule_config} 的真实列：id / tenant_id / business_type / store_id /
 * require_approval / version / idempotency_key / status
 * + created_at / updated_at（记账信息，不进领域模型）。
 *
 * <p>作用域语义（解析顺序 门店覆盖 → 业态默认 → 租户默认，见 {@link #scope()}）：
 * <ul>
 *   <li>{@code store_id > 0} + 非空 {@code business_type}：门店覆盖（STORE）；</li>
 *   <li>{@code store_id = 0} + 非空 {@code business_type}：业态默认（BUSINESS）；</li>
 *   <li>{@code store_id = 0} + 空 {@code business_type}：租户默认（TENANT）。</li>
 * </ul>
 *
 * @param id              规则标识
 * @param tenantId        租户
 * @param businessType    业态（租户默认档为空串）
 * @param storeId         门店（0 = 非门店档）
 * @param requireApproval 是否要求先提交作废审批
 * @param version         乐观锁版本号
 * @param idempotencyKey  幂等键（保存路径的重试语义）
 * @param status          状态（生效中为 {@code ACTIVE}）
 */
public record VoidRuleConfig(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    Boolean requireApproval,
    Integer version,
    String idempotencyKey,
    String status) {

    /** 生效中：只有这一状态参与规则解析。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 是否要求先提交作废审批（空视为「不要求」，与改造前 {@code Boolean.TRUE.equals} 同口径）。 */
    public boolean approvalRequired() {
        return Boolean.TRUE.equals(requireApproval);
    }

    /**
     * 该行的作用域档位：{@code STORE} / {@code BUSINESS} / {@code TENANT}。
     *
     * <p>作用域由列值推导而不是另存一列：{@code store_id}/{@code business_type} 的组合本身就唯一确定了档位，
     * 多一列只会多一处可能与定位口径不一致的状态。
     */
    public String scope() {
        if (storeId != null && storeId > 0) {
            return "STORE";
        }
        return businessType == null || businessType.isBlank() ? "TENANT" : "BUSINESS";
    }

    /** 版本号；为空视为 0。 */
    public int currentVersion() {
        return version == null ? 0 : version;
    }

    /** 覆盖「是否要求审批」并推进版本（CAS 成功后的新实例）。 */
    public VoidRuleConfig withRequireApproval(Boolean newRequireApproval, int newVersion) {
        return new VoidRuleConfig(id, tenantId, businessType, storeId, newRequireApproval, newVersion,
            idempotencyKey, status);
    }
}
