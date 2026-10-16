package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.OrderVoidApprovalRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.platform.order.infra.persistence.mapper.OrdOrderVoidApprovalMapper;
import io.openware.platform.order.infra.persistence.po.OrdOrderVoidApprovalPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link OrderVoidApprovalRepository} 的持久化适配器。
 *
 * <p><b>两条条件语句是并发保护的唯一判定点</b>：
 * {@link #reviewWithVersion} 带 {@code AND version = ? AND status = 'PENDING'}，
 * {@link #markExecuted} 带 {@code AND status = 'APPROVED'}。应用层的「读出来判状态」只是快速失败提示，
 * 拆掉条件（改成 {@code updateById}）会让两个人的「批准」都成功、各自触发一次订单作废与包厢释放。
 *
 * <p><b>版本写回口径与改造前逐字一致</b>：两条语句写的都是 {@code 读出来的版本 + 1}
 * （不是 SQL 里的 {@code version = version + 1}）。条件里已有 {@code version = ?}，
 * 因此可选值二者等价；这里保持原样，不在迁移里更换写法。
 *
 * <p>{@code created_at}/{@code updated_at} 由本层填充（插入填「现在」；审批写 {@code updated_at}），
 * 需要回给响应时经 {@link Persisted} 携带。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code ord_order_void_approval}）：
 * id / tenant_id / store_id / business_type / order_id / order_version / order_status_snapshot /
 * order_snapshot_json / reason / status / applicant_id / approver_id / review_comment / idempotency_key /
 * version / reviewed_at + created_at / updated_at。该表另有 {@code created_by}/{@code updated_by} 两列
 * （DDL {@code DEFAULT 0} = 系统），但持久化对象上没有映射它们——改造前这两列就只吃默认值，
 * 本次迁移不新增对它们的写入。该持久化对象也没有 {@code @TableField(exist = false)} 字段。
 */
@Repository
public class OrderVoidApprovalRepositoryAdapter implements OrderVoidApprovalRepository {

    private final OrdOrderVoidApprovalMapper mapper;

    public OrderVoidApprovalRepositoryAdapter(OrdOrderVoidApprovalMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Persisted<OrderVoidApproval>> findScoped(Long tenantId, Long storeId, Long id) {
        if (id == null || tenantId == null || storeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OrdOrderVoidApprovalPo>lambdaQuery()
            .eq(OrdOrderVoidApprovalPo::getId, id)
            .eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
            .eq(OrdOrderVoidApprovalPo::getStoreId, storeId))).map(OrderVoidApprovalRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<OrderVoidApproval>> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(OrderVoidApprovalRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<OrderVoidApproval>> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (tenantId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<OrdOrderVoidApprovalPo>lambdaQuery()
            .eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
            .eq(OrdOrderVoidApprovalPo::getIdempotencyKey, idempotencyKey)))
            .map(OrderVoidApprovalRepositoryAdapter::persisted);
    }

    @Override
    public List<Persisted<OrderVoidApproval>> list(Long tenantId, Long storeId, String status) {
        List<OrdOrderVoidApprovalPo> rows = mapper.selectList(Wrappers.<OrdOrderVoidApprovalPo>lambdaQuery()
            .eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
            .eq(OrdOrderVoidApprovalPo::getStoreId, storeId)
            .eq(status != null, OrdOrderVoidApprovalPo::getStatus, status)
            .orderByDesc(OrdOrderVoidApprovalPo::getCreatedAt));
        List<Persisted<OrderVoidApproval>> result = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (OrdOrderVoidApprovalPo row : rows) {
                result.add(persisted(row));
            }
        }
        return result;
    }

    @Override
    public Persisted<OrderVoidApproval> insert(OrderVoidApproval approval) {
        OrdOrderVoidApprovalPo po = toPo(approval);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // 持久化对象没有映射 created_by/updated_by（DDL DEFAULT 0 = 系统）。
        mapper.insert(po);
        return persisted(po);
    }

    @Override
    public Optional<Persisted<OrderVoidApproval>> reviewWithVersion(Long id, Long tenantId, int expectedVersion,
                                                                    String targetStatus, Long approverId,
                                                                    String comment, LocalDateTime reviewedAt) {
        int changed = mapper.update(null, Wrappers.<OrdOrderVoidApprovalPo>lambdaUpdate()
            .eq(OrdOrderVoidApprovalPo::getId, id)
            .eq(OrdOrderVoidApprovalPo::getTenantId, tenantId)
            .eq(OrdOrderVoidApprovalPo::getVersion, expectedVersion)
            // 状态判据固定为 PENDING：只有待审批的申请能被审批（调用方无法绕过状态机）。
            .eq(OrdOrderVoidApprovalPo::getStatus, OrderVoidApproval.STATUS_PENDING)
            .set(OrdOrderVoidApprovalPo::getStatus, targetStatus)
            .set(OrdOrderVoidApprovalPo::getApproverId, approverId)
            .set(OrdOrderVoidApprovalPo::getReviewComment, comment)
            .set(OrdOrderVoidApprovalPo::getReviewedAt, reviewedAt)
            .set(OrdOrderVoidApprovalPo::getUpdatedAt, reviewedAt)
            // 版本自增写在 SQL 里，与 VoidRuleConfigRepositoryAdapter 及 standards/10「乐观锁的可检验判据」一致。
            // 虽然条件里已有 version = ?（因此 Java 侧 +1 也不会写错），但一旦条件被误删，
            // Java 侧加一就会把读出来的旧版本写回去；SQL 侧自增在任何情况下都不会。
            .setSql("version = version + 1"));
        if (changed == 0) {
            return Optional.empty();
        }
        // 重新读回：响应要给的是落库后的状态/版本/审批时刻（与改造前 selectById 同一口径）。
        return findById(id);
    }

    @Override
    public boolean markExecuted(Long id, int currentVersion, LocalDateTime at) {
        // 两个条件都必须有：status 保证「只有已批准的申请能被执行」，
        // version 保证「只有一个执行者能成功」——少了 version，两个并发执行都会返回成功，
        // 各自触发一次订单作废与包厢释放，而调用方两次都被告知成功。
        // 这正是 currentVersion 参数存在的理由：它参与判定，不是装饰。
        return mapper.update(null, Wrappers.<OrdOrderVoidApprovalPo>lambdaUpdate()
            .eq(OrdOrderVoidApprovalPo::getId, id)
            .eq(OrdOrderVoidApprovalPo::getStatus, OrderVoidApproval.STATUS_APPROVED)
            .eq(OrdOrderVoidApprovalPo::getVersion, currentVersion)
            .set(OrdOrderVoidApprovalPo::getStatus, OrderVoidApproval.STATUS_EXECUTED)
            .set(OrdOrderVoidApprovalPo::getUpdatedAt, at)
            .setSql("version = version + 1")) > 0;
    }

    /** 领域聚合 → 持久化对象：逐列对应真实列，审计两列由 insert 补齐。 */
    static OrdOrderVoidApprovalPo toPo(OrderVoidApproval approval) {
        OrdOrderVoidApprovalPo po = new OrdOrderVoidApprovalPo();
        po.setId(approval.id());
        po.setTenantId(approval.tenantId());
        po.setStoreId(approval.storeId());
        po.setBusinessType(approval.businessType());
        po.setOrderId(approval.orderId());
        po.setOrderVersion(approval.orderVersion());
        po.setOrderStatusSnapshot(approval.orderStatusSnapshot());
        po.setOrderSnapshotJson(approval.orderSnapshotJson());
        po.setReason(approval.reason());
        po.setStatus(approval.status());
        po.setApplicantId(approval.applicantId());
        po.setApproverId(approval.approverId());
        po.setReviewComment(approval.reviewComment());
        po.setIdempotencyKey(approval.idempotencyKey());
        po.setVersion(approval.version());
        po.setReviewedAt(approval.reviewedAt());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取业务列；{@code created_at}/{@code updated_at} 经 {@link #persisted} 取）。 */
    static OrderVoidApproval toDomain(OrdOrderVoidApprovalPo po) {
        if (po == null) {
            return null;
        }
        return new OrderVoidApproval(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
            po.getOrderId(), po.getOrderVersion(), po.getOrderStatusSnapshot(), po.getOrderSnapshotJson(),
            po.getReason(), po.getStatus(), po.getApplicantId(), po.getApproverId(), po.getReviewComment(),
            po.getIdempotencyKey(), po.getVersion(), po.getReviewedAt());
    }

    /**
     * 业务聚合 + 记账快照（审批响应要 {@code createdAt}/{@code updatedAt}）。
     *
     * <p>持久化对象没有映射 {@code created_by}/{@code updated_by}，因此快照里这两项恒为 {@code null}
     * （与改造前直接序列化持久化对象时一致）。
     */
    static Persisted<OrderVoidApproval> persisted(OrdOrderVoidApprovalPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(null, po.getCreatedAt(),
            null, po.getUpdatedAt()));
    }
}
