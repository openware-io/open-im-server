package io.openware.platform.order.domain.reservation.model;

/**
 * Order 域预约经营规则（领域模型）：允许提前预约、到店前最少取消/改期分钟数。
 *
 * <p>解析顺序（{@code store_id = 0} 表示租户/业态默认，门店覆盖必须带业态）：
 * 门店覆盖 &gt; 业态默认 &gt; 租户默认，见 {@link #scopeSource()}。
 *
 * <p><b>字段集只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的
 * {@code ord_reservation_rule_config}）：id / tenant_id / business_type / store_id /
 * advance_minutes / cancel_minutes / reschedule_minutes / version / idempotency_key / status
 * + 四个审计列。{@code OrdReservationRuleConfigPo} 上没有 {@code @TableField(exist = false)} 字段。
 *
 * <p>{@code version} 留在模型上：规则的保存是「读出来判版本 → 带版本条件写回」的 CAS，
 * 版本号是业务可见的并发语义（standards/10）。
 *
 * <p>审计四列不在模型上（记账信息，不是业务事实）：由适配器填充、由
 * {@code ReservationRuleConfigRepository.Persisted} 在聚合之外携带。
 *
 * @param id                规则标识；{@code null} 表示尚未落库
 * @param tenantId          租户
 * @param businessType      业态；空字符串 = 租户默认（DDL 默认值，非 {@code null}）
 * @param storeId           门店；{@code 0} = 租户/业态默认，大于 0 = 门店覆盖
 * @param advanceMinutes    允许提前预约分钟数；{@code 0} = 不限制
 * @param cancelMinutes     到店前最少取消分钟数；{@code 0} = 不限制
 * @param rescheduleMinutes 到店前最少改期分钟数；{@code 0} = 不限制
 * @param version           乐观锁版本号（CAS 判定用）
 * @param idempotencyKey    幂等键；{@code null} = 调用方未提供
 * @param status            ACTIVE / DISABLED
 */
public record ReservationRuleConfig(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    Integer advanceMinutes,
    Integer cancelMinutes,
    Integer rescheduleMinutes,
    Integer version,
    String idempotencyKey,
    String status) {

    /** 生效中（解析路径只认这一状态）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 租户/业态默认来源：门店未覆盖。 */
    public static final String SOURCE_TENANT = "TENANT";

    /** 业态默认来源。 */
    public static final String SOURCE_BUSINESS = "BUSINESS";

    /** 门店覆盖来源。 */
    public static final String SOURCE_STORE = "STORE";

    /** 版本号（为空视为 0）。 */
    public int currentVersion() {
        return version == null ? 0 : version;
    }

    /** 是否生效中。 */
    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 该行是门店覆盖还是默认值（对外 {@code source} 字段的口径）。 */
    public String scopeSource() {
        if (storeId != null && storeId > 0) {
            return SOURCE_STORE;
        }
        return businessType == null || businessType.isBlank() ? SOURCE_TENANT : SOURCE_BUSINESS;
    }

    /** 新建规则（尚未落库）：版本 0、状态 ACTIVE；{@code storeId} 为空按 0（租户/业态默认）落库。 */
    public static ReservationRuleConfig draft(Long tenantId, String businessType, Long storeId,
                                              Integer advanceMinutes, Integer cancelMinutes,
                                              Integer rescheduleMinutes, String idempotencyKey) {
        return new ReservationRuleConfig(null, tenantId, businessType == null ? "" : businessType,
            storeId == null ? 0L : storeId, advanceMinutes, cancelMinutes, rescheduleMinutes, 0,
            idempotencyKey, STATUS_ACTIVE);
    }

    /** 落库后回填主键（返回新实例）。 */
    public ReservationRuleConfig withId(Long newId) {
        return new ReservationRuleConfig(newId, tenantId, businessType, storeId, advanceMinutes, cancelMinutes,
            rescheduleMinutes, version, idempotencyKey, status);
    }

    /** 覆盖三个窗口值（返回新实例，版本 +1）；范围校验与幂等由应用层负责。 */
    public ReservationRuleConfig updatedWindows(Integer newAdvanceMinutes, Integer newCancelMinutes,
                                                Integer newRescheduleMinutes) {
        return new ReservationRuleConfig(id, tenantId, businessType, storeId, newAdvanceMinutes, newCancelMinutes,
            newRescheduleMinutes, currentVersion() + 1, idempotencyKey, status);
    }
}
