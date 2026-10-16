package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository.AuditSnapshot;
import java.time.LocalDateTime;

/**
 * 预约经营规则一行的对外视图（内部端口 {@code /internal/order/reservation-rules} 的 PUT 响应）。
 *
 * <p><b>字段集照搬原持久化对象 {@code OrdReservationRuleConfigPo}（JSON 字段名一个不改）</b>：
 * BFF（{@code platform-admin-service} 的 {@code RestKtvConfigDomainClient}）按
 * {@code id}/{@code storeId}/{@code businessType}/{@code advanceMinutes}/{@code cancelMinutes}/
 * {@code rescheduleMinutes}/{@code version}/{@code idempotencyKey} 反序列化，
 * 因此这些名字是契约。
 *
 * <p>审计四列是记账信息、不进领域模型（standards/10），由视图在装配期从
 * {@link AuditSnapshot} 取；原实现把它们（{@code createdBy}/{@code updatedBy} 为 {@code null}）
 * 直接由持久化对象序列化，本视图保持同一字段集。
 */
public record ReservationRuleConfigView(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    Integer advanceMinutes,
    Integer cancelMinutes,
    Integer rescheduleMinutes,
    Integer version,
    String idempotencyKey,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

    /** 由领域聚合 + 记账快照构造。 */
    public static ReservationRuleConfigView from(ReservationRuleConfig config, AuditSnapshot audit) {
        if (config == null) {
            return null;
        }
        return new ReservationRuleConfigView(config.id(), config.tenantId(), config.businessType(), config.storeId(),
            config.advanceMinutes(), config.cancelMinutes(), config.rescheduleMinutes(), config.version(),
            config.idempotencyKey(), config.status(),
            audit == null ? null : audit.createdBy(),
            audit == null ? null : audit.createdAt(),
            audit == null ? null : audit.updatedBy(),
            audit == null ? null : audit.updatedAt());
    }
}
