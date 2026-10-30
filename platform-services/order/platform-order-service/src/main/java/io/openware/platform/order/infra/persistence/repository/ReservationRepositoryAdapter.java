package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.infra.persistence.mapper.ReservationMapper;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Repository;

/**
 * {@link ReservationRepository} 的持久化适配器。
 *
 * <p><b>CAS 是本文件存在的理由</b>：预约的写路径全是「读出来判定 → 带 {@code version} 条件写回」，
 * 三个 {@code update*WithVersion} 发单条 {@code WHERE id = ? AND version = ?} 的条件 UPDATE，
 * 版本自增写在 SQL 里（{@code version = version + 1}）。改造前这三条已经是条件更新，
 * 本适配器**逐列复刻**它们的列集合，不借迁移顺手扩大写入范围：
 * 状态流转不写 {@code resource_id}/{@code start_at}/{@code end_at}，
 * 分配包厢不写 {@code status}——每一列越界都意味着一条并发路径会覆盖另一条的写入。
 *
 * <p><b>非列字段绝不进入写入</b>：{@code ReservationPo} 上的三个
 * {@code roomTypeCode} / {@code roomTypeName} / {@code resourceName} 标了
 * {@code @TableField(exist = false)}（DDL 里也没有这三列），本适配器对它们**一个都不碰**：
 * 展示名由读路径从资源域回填到 {@code ReservationView}。对这类字段生成列引用会让 MyBatis-Plus
 * 在运行时抛 {@code can not find lambda cache for this property [...]}
 * （钱包切片的 CAS 适配器栽过一次：{@code tokenAmount}/{@code tokenBrandName}/{@code accountOpened}）。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ktv_reservation}）：
 * id / tenant_id / store_id / reservation_no / idempotency_key / customer_id / business_type /
 * resource_id / room_type_id / start_at / end_at / party_size / contact / status / order_id /
 * arrived_at / version + created_by / created_at / updated_by / updated_at。
 */
@Repository
public class ReservationRepositoryAdapter implements ReservationRepository {

    private final ReservationMapper mapper;

    public ReservationRepositoryAdapter(ReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Persisted<Reservation>> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id)).map(ReservationRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<Reservation>> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return mapper.selectList(Wrappers.<ReservationPo>lambdaQuery()
                .eq(ReservationPo::getTenantId, tenantId)
                .eq(ReservationPo::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"))
            .stream().findFirst().map(ReservationRepositoryAdapter::persisted);
    }

    @Override
    public Optional<Persisted<Reservation>> findByReservationNo(Long tenantId, String reservationNo) {
        if (reservationNo == null || reservationNo.isBlank()) {
            return Optional.empty();
        }
        return mapper.selectList(Wrappers.<ReservationPo>lambdaQuery()
                .eq(ReservationPo::getTenantId, tenantId)
                .eq(ReservationPo::getReservationNo, reservationNo)
                .last("LIMIT 1"))
            .stream().findFirst().map(ReservationRepositoryAdapter::persisted);
    }

    @Override
    public List<Persisted<Reservation>> findMine(Long customerId) {
        return persistedList(mapper.selectList(Wrappers.<ReservationPo>lambdaQuery()
            .eq(ReservationPo::getCustomerId, customerId)
            .orderByDesc(ReservationPo::getStartAt)));
    }

    @Override
    public List<Persisted<Reservation>> findByStartAtRange(LocalDateTime fromInclusive,
                                                            LocalDateTime toInclusive,
                                                            int limit,
                                                            int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        // 时间条件落在 start_at 列上、不用函数包裹，保持索引可用（与改造前的口径一致）。
        return persistedList(mapper.selectList(Wrappers.<ReservationPo>lambdaQuery()
            .ge(fromInclusive != null, ReservationPo::getStartAt, fromInclusive)
            .le(toInclusive != null, ReservationPo::getStartAt, toInclusive)
            .orderByDesc(ReservationPo::getStartAt)
            .last("LIMIT " + safeLimit + " OFFSET " + safeOffset)));
    }

    @Override
    public List<Reservation> findNotArrivedOverdue(LocalDateTime cutoff) {
        // 跨租户一次扫出（语句本身忽略租户行拦截）：每行自带 tenantId，调用方逐行设上下文再写。
        return mapper.selectNotArrivedOverdue(cutoff).stream()
            .map(ReservationRepositoryAdapter::toDomain)
            .toList();
    }

    @Override
    public long countSlotHolding(Long tenantId, Long storeId, Long roomTypeId, LocalDateTime startAt,
                                 LocalDateTime endAt) {
        // 状态集合来自领域枚举（occupiesSlot）：CONVERTED 不算名额，否则「同一间房算两次」——
        // 客人开台后这个时段会一直显示已满，C 端约不出房。
        Long count = mapper.selectCount(Wrappers.<ReservationPo>lambdaQuery()
            .eq(ReservationPo::getTenantId, tenantId)
            .eq(ReservationPo::getStoreId, storeId)
            .eq(ReservationPo::getRoomTypeId, roomTypeId)
            .in(ReservationPo::getStatus, storedNames(ReservationStatus::occupiesSlot))
            .lt(ReservationPo::getStartAt, endAt)
            .gt(ReservationPo::getEndAt, startAt));
        return count == null ? 0L : count;
    }

    @Override
    public Optional<Reservation> findRoomOverlap(Long storeId, Long resourceId, Long excludeReservationId,
                                                 LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.selectList(roomOverlapQuery(storeId, excludeReservationId, startAt, endAt)
                .eq(ReservationPo::getResourceId, resourceId))
            .stream().findFirst().map(ReservationRepositoryAdapter::toDomain);
    }

    @Override
    public Map<Long, Reservation> findRoomOverlaps(Long storeId, Long excludeReservationId,
                                                   LocalDateTime startAt, LocalDateTime endAt) {
        Map<Long, Reservation> result = new LinkedHashMap<>();
        List<ReservationPo> conflicts = mapper.selectList(roomOverlapQuery(storeId, excludeReservationId, startAt, endAt)
            .isNotNull(ReservationPo::getResourceId));
        if (conflicts != null) {
            for (ReservationPo conflict : conflicts) {
                result.putIfAbsent(conflict.getResourceId(), toDomain(conflict));
            }
        }
        return result;
    }

    /**
     * 时段冲突查询的共同条件（按 {@code start_at} 升序，左闭右开的重叠判定）：
     * 同门店、排除自己、状态仍占时段、{@code existing.start_at < endAt && existing.end_at > startAt}。
     */
    private static LambdaQueryWrapper<ReservationPo> roomOverlapQuery(
            Long storeId, Long excludeReservationId, LocalDateTime startAt, LocalDateTime endAt) {
        return Wrappers.<ReservationPo>lambdaQuery()
            .eq(ReservationPo::getStoreId, storeId)
            .ne(excludeReservationId != null, ReservationPo::getId, excludeReservationId)
            .in(ReservationPo::getStatus, storedNames(ReservationStatus::occupiesSlot))
            .lt(ReservationPo::getStartAt, endAt)
            .gt(ReservationPo::getEndAt, startAt)
            .orderByAsc(ReservationPo::getStartAt);
    }

    @Override
    public Persisted<Reservation> insert(Reservation reservation, WriteAudit audit) {
        ReservationPo po = toPo(reservation);
        po.setCreatedBy(audit.operatorId());
        po.setCreatedAt(audit.at());
        po.setUpdatedBy(audit.operatorId());
        po.setUpdatedAt(audit.at());
        // 幂等唯一键（tenant_id, idempotency_key）冲突必须抛出：调用方据此重查返回既有预约。
        mapper.insert(po);
        return persisted(po);
    }

    @Override
    public boolean updateStatusWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit) {
        var update = Wrappers.<ReservationPo>lambdaUpdate()
            .eq(ReservationPo::getId, reservation.id())
            .eq(ReservationPo::getVersion, expectedVersion)
            .set(ReservationPo::getStatus, reservation.status().name())
            .set(ReservationPo::getUpdatedBy, audit.operatorId())
            .set(ReservationPo::getUpdatedAt, audit.at())
            // 订单回填/到店时间只在非空时写列：为空表示「保持原值」，而不是把它清成 null。
            .set(reservation.orderId() != null, ReservationPo::getOrderId, reservation.orderId())
            .set(reservation.arrivedAt() != null, ReservationPo::getArrivedAt, reservation.arrivedAt())
            // 版本自增写在 SQL 里：用读出来的值 +1 会在并发窗口内写回过期版本号。
            .setSql("version = version + 1");
        return mapper.update(null, update) > 0;
    }

    @Override
    public boolean updateAssignmentWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit) {
        // 只写 resource_id 与记账列：状态不在更新集合里（锁房 ≠ 到店）。
        var update = Wrappers.<ReservationPo>lambdaUpdate()
            .eq(ReservationPo::getId, reservation.id())
            .eq(ReservationPo::getVersion, expectedVersion)
            .set(ReservationPo::getResourceId, reservation.resourceId())
            .set(ReservationPo::getUpdatedBy, audit.operatorId())
            .set(ReservationPo::getUpdatedAt, audit.at())
            .setSql("version = version + 1");
        return mapper.update(null, update) > 0;
    }

    @Override
    public boolean updateScheduleWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit) {
        var update = Wrappers.<ReservationPo>lambdaUpdate()
            .eq(ReservationPo::getId, reservation.id())
            .eq(ReservationPo::getVersion, expectedVersion)
            .set(ReservationPo::getStartAt, reservation.startAt())
            .set(ReservationPo::getEndAt, reservation.endAt())
            .set(ReservationPo::getUpdatedBy, audit.operatorId())
            .set(ReservationPo::getUpdatedAt, audit.at())
            .setSql("version = version + 1");
        return mapper.update(null, update) > 0;
    }

    /** 领域聚合 → 持久化对象：**只写真实列**（三个展示字段连设都不设，见类注释）。 */
    static ReservationPo toPo(Reservation reservation) {
        ReservationPo po = new ReservationPo();
        po.setId(reservation.id());
        po.setTenantId(reservation.tenantId());
        po.setStoreId(reservation.storeId());
        po.setReservationNo(reservation.reservationNo());
        po.setIdempotencyKey(reservation.idempotencyKey());
        po.setCustomerId(reservation.customerId());
        po.setBusinessType(reservation.businessType());
        po.setResourceId(reservation.resourceId());
        po.setRoomTypeId(reservation.roomTypeId());
        po.setStartAt(reservation.startAt());
        po.setEndAt(reservation.endAt());
        po.setPartySize(reservation.partySizeOrDefault());
        po.setContact(reservation.contact());
        po.setStatus(reservation.status().name());
        po.setOrderId(reservation.orderId());
        po.setArrivedAt(reservation.arrivedAt());
        po.setVersion(reservation.currentVersion());
        return po;
    }

    /** 持久化对象 → 领域聚合（只取真实列；状态按闭集解析，未知值抛出而不是静默降级）。 */
    static Reservation toDomain(ReservationPo po) {
        if (po == null) {
            return null;
        }
        return new Reservation(po.getId(), po.getTenantId(), po.getStoreId(), po.getReservationNo(),
            po.getIdempotencyKey(), po.getCustomerId(), po.getBusinessType(), po.getResourceId(), po.getRoomTypeId(),
            po.getStartAt(), po.getEndAt(), po.getPartySize(), po.getContact(),
            ReservationStatus.fromStored(po.getStatus()), po.getOrderId(), po.getArrivedAt(), po.getVersion());
    }

    /** 业务聚合 + 记账快照：分开交给上层，领域模型不承载记账字段。 */
    static Persisted<Reservation> persisted(ReservationPo po) {
        return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(),
            po.getUpdatedBy(), po.getUpdatedAt()));
    }

    private static List<Persisted<Reservation>> persistedList(List<ReservationPo> rows) {
        List<Persisted<Reservation>> result = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (ReservationPo row : rows) {
                result.add(persisted(row));
            }
        }
        return result;
    }

    /** 领域状态口径 → 库中状态字符串集合（状态集合只在 {@link ReservationStatus} 里定义一次）。 */
    private static List<String> storedNames(Predicate<ReservationStatus> predicate) {
        List<String> names = new ArrayList<>();
        for (ReservationStatus status : ReservationStatus.values()) {
            if (predicate.test(status)) {
                names.add(status.name());
            }
        }
        return names;
    }
}
