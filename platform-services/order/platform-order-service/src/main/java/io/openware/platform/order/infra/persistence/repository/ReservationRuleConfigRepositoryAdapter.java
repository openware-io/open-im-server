package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.reservation.model.ReservationRuleConfig;
import io.openware.platform.order.domain.reservation.repository.ReservationRuleConfigRepository;
import io.openware.platform.order.infra.persistence.mapper.OrdReservationRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdReservationRuleConfigPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ReservationRuleConfigRepository} 的持久化适配器。
 *
 * <p><b>CAS 是核心</b>：规则的保存是「读出来判版本 → 带版本条件写回」，
 * {@link #updateWindowsWithVersion} 发单条 {@code WHERE id = ? AND version = ?} 的条件 UPDATE，
 * 版本自增写在 SQL 里。受影响 0 行返回 {@link Optional#empty()}：调用方必须抛版本冲突，
 * 而不是把本次值当成生效值——预约窗口决定「客人能不能下单/取消」。
 *
 * <p><b>审计列的口径与改造前逐字一致</b>：新增写 {@code created_at}/{@code updated_at}，
 * 覆盖写刷新 {@code updated_at}；{@code created_by}/{@code updated_by} <b>不写</b>
 * （DDL 是 {@code NOT NULL DEFAULT 0}，0 = 系统，改造前的实现也没写这两列）——迁移不顺手新增审计列写入。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的
 * {@code ktv_reservation_rule_config}）：id / tenant_id / business_type / store_id /
 * advance_minutes / cancel_minutes / reschedule_minutes / version / idempotency_key / status
 * + created_by / created_at / updated_by / updated_at。{@code OrdReservationRuleConfigPo}
 * 上没有 {@code @TableField(exist = false)} 字段。
 */
@Repository
public class ReservationRuleConfigRepositoryAdapter implements ReservationRuleConfigRepository {

    private final OrdReservationRuleConfigMapper mapper;

    public ReservationRuleConfigRepositoryAdapter(OrdReservationRuleConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Persisted<ReservationRuleConfig>> findActive(Long tenantId, String businessType, Long storeId) {
        return mapper.selectList(Wrappers.<OrdReservationRuleConfigPo>lambdaQuery()
                .eq(OrdReservationRuleConfigPo::getTenantId, tenantId)
                .eq(OrdReservationRuleConfigPo::getBusinessType, businessType)
                .eq(OrdReservationRuleConfigPo::getStoreId, storeId)
                .eq(OrdReservationRuleConfigPo::getStatus, ReservationRuleConfig.STATUS_ACTIVE)
                .last("LIMIT 1"))
            .stream().findFirst().map(ReservationRuleConfigRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<ReservationRuleConfig>> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return mapper.selectList(Wrappers.<OrdReservationRuleConfigPo>lambdaQuery()
                .eq(OrdReservationRuleConfigPo::getTenantId, tenantId)
                .eq(OrdReservationRuleConfigPo::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"))
            .stream().findFirst().map(ReservationRuleConfigRepositoryAdapter::persisted);
    }

    @Override
    public Persisted<ReservationRuleConfig> insert(ReservationRuleConfig config) {
        OrdReservationRuleConfigPo po = toPo(config);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // 作用域唯一键（tenant_id, business_type, store_id）与幂等唯一键冲突必须抛出，
        // 调用方据此重查返回既有行。
        mapper.insert(po);
        return persisted(po);
    }

    @Override
    public Optional<Persisted<ReservationRuleConfig>> updateWindowsWithVersion(ReservationRuleConfig config,
                                                                              int expectedVersion) {
        // 不可变列（created_by/created_at/updated_by）从库里取回原值原样带上：响应契约要它们，
        // 而它们不参与本次判定。CAS 仍然由下面的 WHERE version = ? 决定成败。
        OrdReservationRuleConfigPo existing = config.id() == null ? null : mapper.selectById(config.id());
        LocalDateTime now = LocalDateTime.now();
        var update = Wrappers.<OrdReservationRuleConfigPo>lambdaUpdate()
            .eq(OrdReservationRuleConfigPo::getId, config.id())
            .eq(OrdReservationRuleConfigPo::getVersion, expectedVersion)
            .set(OrdReservationRuleConfigPo::getAdvanceMinutes, config.advanceMinutes())
            .set(OrdReservationRuleConfigPo::getCancelMinutes, config.cancelMinutes())
            .set(OrdReservationRuleConfigPo::getRescheduleMinutes, config.rescheduleMinutes())
            .set(OrdReservationRuleConfigPo::getUpdatedAt, now)
            // 版本自增写在 SQL 里：用读出来的值 +1 会在并发窗口内写回过期版本号。
            .setSql("version = version + 1");
        if (mapper.update(null, update) == 0) {
            return Optional.empty();
        }
        OrdReservationRuleConfigPo written = toPo(config);
        written.setVersion(expectedVersion + 1);
        written.setUpdatedAt(now);
        if (existing != null) {
            written.setCreatedBy(existing.getCreatedBy());
            written.setCreatedAt(existing.getCreatedAt());
            written.setUpdatedBy(existing.getUpdatedBy());
        }
        return Optional.of(persisted(written));
    }

    /** 领域聚合 → 持久化对象：只写业务列，审计列由 insert/update 按落库口径补齐。 */
    static OrdReservationRuleConfigPo toPo(ReservationRuleConfig config) {
        OrdReservationRuleConfigPo po = new OrdReservationRuleConfigPo();
        po.setId(config.id());
        po.setTenantId(config.tenantId());
        po.setBusinessType(config.businessType());
        po.setStoreId(config.storeId());
        po.setAdvanceMinutes(config.advanceMinutes());
        po.setCancelMinutes(config.cancelMinutes());
        po.setRescheduleMinutes(config.rescheduleMinutes());
        po.setVersion(config.currentVersion());
        po.setIdempotencyKey(config.idempotencyKey());
        po.setStatus(config.status());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取业务列）。 */
    static ReservationRuleConfig toDomain(OrdReservationRuleConfigPo po) {
        if (po == null) {
            return null;
        }
        return new ReservationRuleConfig(po.getId(), po.getTenantId(), po.getBusinessType(), po.getStoreId(),
            po.getAdvanceMinutes(), po.getCancelMinutes(), po.getRescheduleMinutes(), po.getVersion(),
            po.getIdempotencyKey(), po.getStatus());
    }

    /** 业务聚合 + 记账快照：分开交给上层，领域模型不承载记账字段。 */
    static Persisted<ReservationRuleConfig> persisted(OrdReservationRuleConfigPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(),
            po.getUpdatedBy(), po.getUpdatedAt()));
    }
}
