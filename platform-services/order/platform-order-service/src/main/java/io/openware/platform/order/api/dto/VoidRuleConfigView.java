package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import java.time.LocalDateTime;

/**
 * 作废审批规则一行的对外视图（内部端口 {@code /internal/order/void-rules} 的 PUT 响应）。
 *
 * <p><b>字段集照搬原持久化对象 {@code OrdVoidRuleConfigPo}（JSON 字段名一个不改）</b>：
 * BFF（{@code platform-admin-service}）按 {@code id}/{@code tenantId}/{@code businessType}/{@code storeId}/
 * {@code requireApproval}/{@code version}/{@code idempotencyKey}/{@code status} 反序列化，
 * 这四个记账之外的字段名就是契约。
 *
 * <p>审计四列是记账信息、不进领域模型（standards/10），由视图在装配期从 {@link AuditSnapshot} 取；
 * 原实现把它们（{@code createdBy}/{@code updatedBy} 为 {@code null}）直接由持久化对象序列化，
 * 本视图保持同一字段集与同一取值口径。
 */
public record VoidRuleConfigView(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    Boolean requireApproval,
    Integer version,
    String idempotencyKey,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

    /** 由领域聚合 + 记账快照构造（{@code audit} 为 {@code null} 时四个记账列为 {@code null}）。 */
    public static VoidRuleConfigView from(VoidRuleConfig config, AuditSnapshot audit) {
        if (config == null) {
            return null;
        }
        return new VoidRuleConfigView(config.id(), config.tenantId(), config.businessType(), config.storeId(),
            config.requireApproval(), config.version(), config.idempotencyKey(), config.status(),
            audit == null ? null : audit.createdBy(),
            audit == null ? null : audit.createdAt(),
            audit == null ? null : audit.updatedBy(),
            audit == null ? null : audit.updatedAt());
    }
}
