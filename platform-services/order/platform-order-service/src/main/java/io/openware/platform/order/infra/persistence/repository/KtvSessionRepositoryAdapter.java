package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.infra.persistence.mapper.KtvSessionMapper;
import io.openware.platform.order.infra.persistence.po.KtvSessionPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link KtvSessionRepository} 的持久化适配器。
 *
 * <p><b>并发语句逐字保留</b>：{@link #updateWithVersion(KtvSession)} 与
 * {@link #transferRoom(KtvSession)} 直接调用 {@code KtvSessionMapper} 上的
 * {@code @Update} 语句——{@code UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ?}。
 * 本适配器只做「领域模型 ↔ 持久化对象」的搬运，不改写、不拆分、不合并那两条 SQL，
 * 也不在 Java 侧「先读版本再判断」（那样两次并发都会读到同一版本）。
 *
 * <p><b>审计列由本层负责</b>：{@code created_by / created_at / updated_by / updated_at} 是记账信息，
 * 不是业务事实（standards/10），领域模型没有它们。{@link #insert} 写 {@code now}；
 * {@link #updateWithVersion} / {@link #transferRoom} / {@link #update} 刷新 {@code updated_at}
 * （{@code created_*} 不参与 SET，因此不会被 null 覆盖，见 {@link #update(KtvSession)}）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_ktv_session}）：
 * id / tenant_id / store_id / business_type / order_id / room_resource_id / occupation_id /
 * room_name_snapshot / room_code_snapshot / party_size / server_id / server_name / reserved_start_at /
 * reserved_end_at / opened_at / closed_at / billing_unit / billing_start_at / free_wait_minutes /
 * paused_seconds / pause_started_at / overtime_rate / billing_rule_snapshot_json / status / version
 * + created_by / created_at / updated_by / updated_at。**没有** {@code elapsed_seconds} /
 * {@code estimated_room_fee} 两列——原持久化对象上标 {@code @TableField(exist = false)} 的展示字段
 * 已随本次迁移移到响应视图，本层与领域模型都不再承载，因此不可能对不存在的列生成引用。
 */
@Repository
public class KtvSessionRepositoryAdapter implements KtvSessionRepository {

    private final KtvSessionMapper mapper;

    public KtvSessionRepositoryAdapter(KtvSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<KtvSession> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(KtvSessionRepositoryAdapter::toDomain);
    }

    @Override
    public Long findTenantIdById(Long id) {
        return id == null ? null : mapper.selectTenantIdById(id);
    }

    @Override
    public Optional<KtvSession> findLatestByOrderId(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByOrderId(orderId)).map(KtvSessionRepositoryAdapter::toDomain);
    }

    @Override
    public List<KtvSession> listByOrderId(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<KtvSessionPo>lambdaQuery().eq(KtvSessionPo::getOrderId, orderId))
                .stream().map(KtvSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<KtvSession> listByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            // 空集合不能下推：会拼出非法的 IN ()，调用方按「没有会话」处理。
            return List.of();
        }
        return mapper.selectList(Wrappers.<KtvSessionPo>lambdaQuery().in(KtvSessionPo::getOrderId, orderIds))
                .stream().map(KtvSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<KtvSession> findOpenSessions() {
        return mapper.selectOpenSessions().stream().map(KtvSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<KtvSession> findReservedOverdue(LocalDateTime cutoff) {
        return mapper.selectReservedOverdue(cutoff).stream().map(KtvSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<KtvSession> findOpenedBetween(Long tenantId, LocalDateTime fromInclusive,
            LocalDateTime toExclusive) {
        return mapper.selectList(Wrappers.<KtvSessionPo>lambdaQuery()
                        .eq(KtvSessionPo::getTenantId, tenantId)
                        // 时间条件直接落在 opened_at 列上，不用函数包裹（保持索引可用，口径与迁移前一致）。
                        .ge(KtvSessionPo::getOpenedAt, fromInclusive)
                        .lt(KtvSessionPo::getOpenedAt, toExclusive))
                .stream().map(KtvSessionRepositoryAdapter::toDomain).toList();
    }

    @Override
    public KtvSession insert(KtvSession session) {
        KtvSessionPo po = toPo(session);
        if (po.getVersion() == null) {
            po.setVersion(0);
        }
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        // created_by/updated_by 留空：DDL 是 NOT NULL DEFAULT 0（0 = 系统），迁移前此处亦不写。
        mapper.insert(po);
        // 主键由 MyBatis-Plus 回填到持久化对象上；领域模型不可变，用 withId 返回新实例。
        return session.withId(po.getId());
    }

    @Override
    public boolean updateWithVersion(KtvSession session) {
        KtvSessionPo po = toPo(session);
        po.setUpdatedAt(LocalDateTime.now());
        // 条件 UPDATE：受影响 0 行 = 并发冲突，信号原样交给调用方，本层不吞、不重试。
        return mapper.updateWithVersion(po) > 0;
    }

    @Override
    public boolean transferRoom(KtvSession session) {
        KtvSessionPo po = toPo(session);
        po.setUpdatedAt(LocalDateTime.now());
        return mapper.transferRoom(po) > 0;
    }

    @Override
    public void update(KtvSession session) {
        KtvSessionPo po = toPo(session);
        po.setUpdatedAt(LocalDateTime.now());
        // 实体更新（NOT_NULL 策略）：null 字段不写列。created_at / created_by 因此不出现在 SET 里，
        // 库中已有值原样保留——它与迁移前逐列写回「读出来的旧值」在数据库上的最终结果一致。
        // version 会按领域模型里的当前值写回，见 KtvSessionRepository#update 记录的已知缺陷。
        mapper.updateById(po);
    }

    @Override
    public void updateServerSnapshot(Long sessionId, Long serverResourceId, String serverName) {
        // updated_at 由适配器在同一条语句里刷成「现在」：审计列不是业务事实，也不该成为端口的参数。
        mapper.updateServerSnapshot(sessionId, serverResourceId, serverName, LocalDateTime.now());
    }

    /** 领域模型 → 持久化对象（逐列对应真实列；没有非列成员可设）。 */
    static KtvSessionPo toPo(KtvSession session) {
        KtvSessionPo po = new KtvSessionPo();
        po.setId(session.id());
        po.setTenantId(session.tenantId());
        po.setOrganizationId(session.organizationId());
        po.setStoreId(session.storeId());
        po.setBusinessType(session.businessType());
        po.setOrderId(session.orderId());
        po.setRoomResourceId(session.roomResourceId());
        po.setOccupationId(session.occupationId());
        po.setRoomNameSnapshot(session.roomNameSnapshot());
        po.setRoomCodeSnapshot(session.roomCodeSnapshot());
        po.setPartySize(session.partySize());
        po.setServerId(session.serverId());
        po.setServerName(session.serverName());
        po.setReservedStartAt(session.reservedStartAt());
        po.setReservedEndAt(session.reservedEndAt());
        po.setOpenedAt(session.openedAt());
        po.setClosedAt(session.closedAt());
        po.setBillingUnit(session.billingUnit());
        po.setBillingStartAt(session.billingStartAt());
        po.setFreeWaitMinutes(session.freeWaitMinutes());
        po.setPausedSeconds(session.pausedSeconds());
        po.setPauseStartedAt(session.pauseStartedAt());
        po.setOvertimeRate(session.overtimeRate());
        po.setBillingRuleSnapshotJson(session.billingRuleSnapshotJson());
        po.setStatus(session.status());
        po.setVersion(session.version());
        return po;
    }

    /** 持久化对象 → 领域模型（只取业务列；审计四列被丢弃）。 */
    static KtvSession toDomain(KtvSessionPo po) {
        if (po == null) {
            return null;
        }
        return new KtvSession(po.getId(), po.getTenantId(), po.getOrganizationId(), po.getStoreId(),
                po.getBusinessType(), po.getOrderId(), po.getRoomResourceId(), po.getOccupationId(),
                po.getRoomNameSnapshot(), po.getRoomCodeSnapshot(), po.getPartySize(), po.getServerId(),
                po.getServerName(), po.getReservedStartAt(), po.getReservedEndAt(), po.getOpenedAt(),
                po.getClosedAt(), po.getBillingUnit(), po.getBillingStartAt(), po.getFreeWaitMinutes(),
                po.getPausedSeconds(), po.getPauseStartedAt(), po.getOvertimeRate(),
                po.getBillingRuleSnapshotJson(), po.getStatus(), po.getVersion());
    }
}
