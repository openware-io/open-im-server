package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.ktv.repository.KtvServerSessionRepository;
import io.openware.platform.order.infra.persistence.mapper.KtvServerSessionMapper;
import io.openware.platform.order.infra.persistence.po.KtvServerSessionPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link KtvServerSessionRepository} 的持久化适配器。
 *
 * <p><b>并发语句逐字保留</b>：{@link #updateWithVersion(KtvServerSession)} 直接调用
 * {@code KtvServerSessionMapper} 上的 {@code @Update} 语句——{@code UPDATE ord_ktv_server_session
 * SET ..., version = version + 1 WHERE id = ? AND version = ?}。适配器只做模型搬运，不改写 SQL，
 * 受影响 0 行的并发冲突信号原样交给调用方。
 *
 * <p><b>审计列由本层负责</b>：领域模型没有 {@code created_by / created_at / updated_by / updated_at}
 * （记账信息，不是业务事实，standards/10）。{@link #insert} 写 {@code now}，
 * {@link #updateWithVersion} 只刷新 {@code updated_at}——{@code created_*} 不在该语句的 SET 里，
 * 库中原值保留。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_ktv_server_session}）：
 * id / tenant_id / store_id / business_type / order_id / ktv_session_id / server_resource_id /
 * catalog_item_id / ordered_at / started_at / ended_at / billing_unit / increment_minutes /
 * rounding_direction / price_per_inc / duration_seconds / duration_minutes / total_amount /
 * currency_code / price_snapshot_json / status / version + created_by / created_at / updated_by /
 * updated_at。原持久化对象的 26 个字段**全部**是列，因此领域模型与表逐列对应，
 * 不存在「对非列字段生成引用」的可能。
 */
@Repository
public class KtvServerSessionRepositoryAdapter implements KtvServerSessionRepository {

    private final KtvServerSessionMapper mapper;

    public KtvServerSessionRepositoryAdapter(KtvServerSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<KtvServerSession> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(KtvServerSessionRepositoryAdapter::toDomain);
    }

    @Override
    public Long findTenantIdById(Long id) {
        return id == null ? null : mapper.selectTenantIdById(id);
    }

    @Override
    public List<KtvServerSession> listByKtvSessionId(Long ktvSessionId) {
        if (ktvSessionId == null) {
            return List.of();
        }
        return mapper.selectByKtvSessionId(ktvSessionId).stream()
                .map(KtvServerSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<KtvServerSession> listByOrderId(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return mapper.selectByOrderId(orderId).stream()
                .map(KtvServerSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public KtvServerSession insert(KtvServerSession session) {
        KtvServerSessionPo po = toPo(session);
        if (po.getVersion() == null) {
            po.setVersion(0);
        }
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return session.withId(po.getId());
    }

    @Override
    public boolean updateWithVersion(KtvServerSession session) {
        KtvServerSessionPo po = toPo(session);
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.updateWithVersion(po) > 0;
    }

    /** 领域模型 → 持久化对象（逐列对应真实列；没有非列成员可设）。 */
    static KtvServerSessionPo toPo(KtvServerSession session) {
        KtvServerSessionPo po = new KtvServerSessionPo();
        po.setId(session.id());
        po.setTenantId(session.tenantId());
        po.setStoreId(session.storeId());
        po.setBusinessType(session.businessType());
        po.setOrderId(session.orderId());
        po.setKtvSessionId(session.ktvSessionId());
        po.setServerResourceId(session.serverResourceId());
        po.setCatalogItemId(session.catalogItemId());
        po.setOrderedAt(session.orderedAt());
        po.setStartedAt(session.startedAt());
        po.setEndedAt(session.endedAt());
        po.setBillingUnit(session.billingUnit());
        po.setIncrementMinutes(session.incrementMinutes());
        po.setRoundingDirection(session.roundingDirection());
        po.setPricePerInc(session.pricePerInc());
        po.setDurationSeconds(session.durationSeconds());
        po.setDurationMinutes(session.durationMinutes());
        po.setTotalAmount(session.totalAmount());
        po.setCurrencyCode(session.currencyCode());
        po.setPriceSnapshotJson(session.priceSnapshotJson());
        po.setStatus(session.status());
        po.setVersion(session.version());
        return po;
    }

    /** 持久化对象 → 领域模型（只取业务列；审计四列被丢弃）。 */
    static KtvServerSession toDomain(KtvServerSessionPo po) {
        if (po == null) {
            return null;
        }
        return new KtvServerSession(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessType(),
                po.getOrderId(), po.getKtvSessionId(), po.getServerResourceId(), po.getCatalogItemId(),
                po.getOrderedAt(), po.getStartedAt(), po.getEndedAt(), po.getBillingUnit(),
                po.getIncrementMinutes(), po.getRoundingDirection(), po.getPricePerInc(), po.getDurationSeconds(),
                po.getDurationMinutes(), po.getTotalAmount(), po.getCurrencyCode(), po.getPriceSnapshotJson(),
                po.getStatus(), po.getVersion());
    }
}
