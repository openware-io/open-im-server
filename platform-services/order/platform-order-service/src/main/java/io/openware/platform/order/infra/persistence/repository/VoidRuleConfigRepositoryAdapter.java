package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.order.model.VoidRuleConfig;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.domain.order.repository.VoidRuleConfigRepository;
import io.openware.platform.order.infra.persistence.mapper.OrdVoidRuleConfigMapper;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link VoidRuleConfigRepository} 的持久化适配器。
 *
 * <p><b>CAS 是核心</b>：规则的保存是「读出来判版本 → 带版本条件写回」，
 * {@link #updateRequireApprovalWithVersion} 发单条 {@code WHERE id = ? AND version = ?} 的条件 UPDATE，
 * 版本自增写在 SQL 里。受影响 0 行返回 {@link Optional#empty()}：调用方必须报版本冲突，
 * 而不是把本次值当成生效值——这条规则决定「作废订单要不要先走审批」。
 *
 * <p><b>审计列口径与改造前逐字一致</b>：新增写 {@code created_at}/{@code updated_at}，
 * 覆盖写只刷新 {@code updated_at}。该表另有 {@code created_by}/{@code updated_by} 两列（DDL {@code DEFAULT 0}
 * = 系统），但持久化对象上没有映射它们——改造前这两列就只吃默认值，本次迁移不新增对它们的写入。
 * 覆盖写为了把不可变的 {@code created_at} 原样带回响应，会先按主键读一次原行，
 * 判定成败的仍是那条 {@code WHERE version = ?}。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code ord_void_rule_config}）：id / tenant_id / business_type /
 * store_id / require_approval / version / idempotency_key / status + created_at / updated_at。
 * 该持久化对象上没有 {@code @TableField(exist = false)} 字段。
 */
@Repository
public class VoidRuleConfigRepositoryAdapter implements VoidRuleConfigRepository {

    private final OrdVoidRuleConfigMapper mapper;

    public VoidRuleConfigRepositoryAdapter(OrdVoidRuleConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Persisted<VoidRuleConfig>> findActive(Long tenantId, String businessType, Long storeId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OrdVoidRuleConfigPo>lambdaQuery()
            .eq(OrdVoidRuleConfigPo::getTenantId, tenantId)
            .eq(OrdVoidRuleConfigPo::getBusinessType, businessType)
            .eq(OrdVoidRuleConfigPo::getStoreId, storeId)
            .eq(OrdVoidRuleConfigPo::getStatus, VoidRuleConfig.STATUS_ACTIVE)
            .last("LIMIT 1"))).map(VoidRuleConfigRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<VoidRuleConfig>> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OrdVoidRuleConfigPo>lambdaQuery()
            .eq(OrdVoidRuleConfigPo::getTenantId, tenantId)
            .eq(OrdVoidRuleConfigPo::getIdempotencyKey, idempotencyKey)
            .last("LIMIT 1"))).map(VoidRuleConfigRepositoryAdapter::persisted);
    }

    @Override
    public Persisted<VoidRuleConfig> insert(VoidRuleConfig config) {
        OrdVoidRuleConfigPo po = toPo(config);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // 作用域唯一键与幂等唯一键冲突必须抛出：调用方据此重查返回既有行。
        mapper.insert(po);
        return persisted(po);
    }

    @Override
    public Optional<Persisted<VoidRuleConfig>> updateRequireApprovalWithVersion(VoidRuleConfig config,
                                                                               int expectedVersion) {
        // 不可变的记账列从库里取回原值原样带上（响应契约要它们，判定成败的仍是 WHERE version = ?）。
        OrdVoidRuleConfigPo existing = config.id() == null ? null : mapper.selectById(config.id());
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.update(null, Wrappers.<OrdVoidRuleConfigPo>lambdaUpdate()
            .eq(OrdVoidRuleConfigPo::getId, config.id())
            .eq(OrdVoidRuleConfigPo::getVersion, expectedVersion)
            .set(OrdVoidRuleConfigPo::getRequireApproval, config.requireApproval())
            .set(OrdVoidRuleConfigPo::getUpdatedAt, now)
            // 版本自增写在 SQL 里：用读出来的值 +1 会在并发窗口内写回过期版本号。
            .setSql("version = version + 1"));
        if (changed == 0) {
            return Optional.empty();
        }
        OrdVoidRuleConfigPo written = toPo(config);
        written.setVersion(expectedVersion + 1);
        written.setUpdatedAt(now);
        if (existing != null) {
            written.setCreatedAt(existing.getCreatedAt());
        }
        return Optional.of(persisted(written));
    }

    /** 领域聚合 → 持久化对象：只写业务列，审计列由 insert/update 按落库口径补齐。 */
    static OrdVoidRuleConfigPo toPo(VoidRuleConfig config) {
        OrdVoidRuleConfigPo po = new OrdVoidRuleConfigPo();
        po.setId(config.id());
        po.setTenantId(config.tenantId());
        po.setBusinessType(config.businessType());
        po.setStoreId(config.storeId());
        po.setRequireApproval(config.requireApproval());
        po.setVersion(config.version());
        po.setIdempotencyKey(config.idempotencyKey());
        po.setStatus(config.status());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取业务列）。 */
    static VoidRuleConfig toDomain(OrdVoidRuleConfigPo po) {
        if (po == null) {
            return null;
        }
        return new VoidRuleConfig(po.getId(), po.getTenantId(), po.getBusinessType(), po.getStoreId(),
            po.getRequireApproval(), po.getVersion(), po.getIdempotencyKey(), po.getStatus());
    }

    /**
     * 业务聚合 + 记账快照（保存接口的响应要 {@code createdAt}/{@code updatedAt}）。
     *
     * <p>持久化对象没有映射 {@code created_by}/{@code updated_by}，因此快照里这两项恒为 {@code null}
     * （与改造前直接序列化持久化对象时一致）。
     */
    static Persisted<VoidRuleConfig> persisted(OrdVoidRuleConfigPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(null, po.getCreatedAt(),
            null, po.getUpdatedAt()));
    }
}
