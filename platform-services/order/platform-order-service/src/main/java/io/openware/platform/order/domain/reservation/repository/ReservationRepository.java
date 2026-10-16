package io.openware.platform.order.domain.reservation.repository;

import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 预约仓储端口。
 *
 * <p>不暴露 MyBatis {@code Wrapper} / PO / Mapper / {@code IPage}：查询以业务语义命名
 * （{@code countSlotHolding}、{@code findRoomOverlap}），应用层只表达「要什么」。
 *
 * <p><b>列集合只来自真实 DDL</b>：见 {@link Reservation} 的说明——{@code roomTypeCode} /
 * {@code roomTypeName} / {@code resourceName} 不是列（PO 上标了 {@code @TableField(exist = false)}），
 * 因此端口上没有任何方法接受或返回它们；它们由读路径从资源域回填到响应视图。
 *
 * <p><b>并发契约</b>：本表的写路径全是「读出来判定 → 带 {@code version} 条件写回」，
 * 三个 {@code update*WithVersion} 方法都必须发**单条** {@code WHERE id = ? AND version = ?} 的
 * 条件 UPDATE（版本自增写在 SQL 里），返回 {@code false} 表示期间已有他人写入，
 * 调用方**必须**按冲突处理：改成「先用读出来的版本号比对、再无条件写回」会让两个并发请求
 * 各自覆盖对方（预约状态机、订单回填与审计留痕都会以失败方的事实为准）。
 *
 * <p><b>读路径为什么也返回 {@link Persisted}</b>：响应契约要求四个记账列
 * （{@code createdBy/createdAt/updatedBy/updatedAt}），而它们不进领域模型（standards/10）。
 * 端口把「业务事实」与「记账值」分开交给上层：只关心聚合的调用方读 {@link Persisted#aggregate()}，
 * 要装配响应的调用方读 {@link Persisted#audit()}。
 */
public interface ReservationRepository {

    /** 按标识取预约（跨租户由租户拦截器过滤为 {@code empty}，调用方按 404 语义处理）。 */
    Optional<Persisted<Reservation>> findById(Long id);

    /**
     * 按租户 + 幂等键取预约（创建路径的重试语义）。
     *
     * <p>租户条件显式落在 {@code tenant_id} 列上：幂等键只在租户内唯一
     * （{@code uk_ord_reservation_idem} 是 {@code (tenant_id, idempotency_key)}），
     * 不做显式租户过滤会把别的租户的同键预约当成「已创建」返回。
     */
    Optional<Persisted<Reservation>> findByIdempotencyKey(Long tenantId, String idempotencyKey);

    /** 按租户 + 预约号取预约（C 端旧契约兼容读路径的定位口径）。 */
    Optional<Persisted<Reservation>> findByReservationNo(Long tenantId, String reservationNo);

    /** 某会员的全部预约，按预约时段开始时间倒序（C 端「我的预约」）。 */
    List<Persisted<Reservation>> findMine(Long customerId);

    /**
     * 按**预约时段开始时间**的闭区间列出预约，倒序。
     *
     * <p>时间条件直接落在 {@code start_at} 列上、不用函数包裹（保持索引可用）；
     * 边界为 {@code null} 表示该侧不筛。租户过滤由租户拦截器追加（与改造前一致）。
     */
    List<Persisted<Reservation>> findByStartAtRange(LocalDateTime fromInclusive, LocalDateTime toInclusive);

    /**
     * 超订软保护计数：同租户 + 同门店 + 同房型 + 时段重叠的**仍占名额**预约数
     * （状态口径是 {@link ReservationStatus#occupiesSlot()}，由适配器固化，见其实现说明）。
     *
     * <p>重叠判定左闭右开：{@code existing.start_at < endAt && existing.end_at > startAt}，
     * 于是「上一场 22:00 结束、下一场 22:00 开始」不算重叠。
     *
     * <p><b>这是 count-then-insert，不是锁</b>：并发创建同一房型最后几个名额时两条请求都可能通过计数。
     * 端口如实表达该口径（与改造前逐字一致），修复它需要唯一键或行锁，属于 schema 变更，不在本次迁移内。
     */
    long countSlotHolding(Long tenantId, Long storeId, Long roomTypeId, LocalDateTime startAt, LocalDateTime endAt);

    /**
     * 指定包厢在本时段内**最早的一条**冲突预约（到店分配包厢的时段冲突判定）。
     *
     * <p>冲突状态口径是 {@link ReservationStatus#occupiesSlot()}：{@code CANCELLED}/{@code NO_SHOW}
     * 已释放时段，{@code CONVERTED}（已开台）的时段归属由资源占用承载，不用预约行重复判一次。
     * 按 {@code start_at} 升序取第一条，便于给出稳定的错误提示。
     *
     * @param excludeReservationId 排除自己（幂等重分配时不用把自己判成冲突）
     */
    Optional<Reservation> findRoomOverlap(Long storeId, Long resourceId, Long excludeReservationId,
                                          LocalDateTime startAt, LocalDateTime endAt);

    /**
     * 本时段内按包厢聚合的冲突预约（一个包厢只保留最早的一条用于提示），键为 {@code resource_id}。
     *
     * <p>用于「分配包厢候选列表」：一次查出本时段所有冲突，而不是每个包厢查一次（N+1）。
     * 只取已分配包厢（{@code resource_id IS NOT NULL}）的行。
     */
    Map<Long, Reservation> findRoomOverlaps(Long storeId, Long excludeReservationId,
                                            LocalDateTime startAt, LocalDateTime endAt);

    /**
     * 新增预约，返回落库后的聚合与记账快照。
     *
     * <p>幂等由唯一键 {@code uk_ord_reservation_idem} 兜底：撞键必须抛出，调用方据此重查返回既有预约
     * （吞掉它会让调用方以为新建成功而实际没有）。
     *
     * @param audit 本次写入的记账值（{@code created_by/created_at/updated_by/updated_at} 都取它）
     */
    Persisted<Reservation> insert(Reservation reservation, WriteAudit audit);

    /**
     * CAS 状态流转：单条 {@code UPDATE ... SET status=?, version=version+1, updated_* WHERE id=? AND version=?}。
     *
     * <p>只写状态列（{@code status}）、订单回填（{@code order_id}，非空时）、到店时间
     * （{@code arrived_at}，非空时）与记账列：**不碰** {@code resource_id}/{@code start_at}/{@code end_at}，
     * 否则「到店登记」会把并发的「分配包厢」结果覆盖回旧值。
     *
     * @param reservation 目标状态与要回填的值（{@code orderId}/{@code arrivedAt} 为 {@code null} = 保持原值）
     * @param expectedVersion 读出来的版本号；只作为条件，不写回
     * @return 是否写入成功；{@code false} = 版本冲突（或行已被删除），调用方必须抛冲突而不是当作成功
     */
    boolean updateStatusWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit);

    /**
     * CAS 写入包厢分配：单条 {@code UPDATE ... SET resource_id=?, version=version+1, updated_* WHERE id=? AND version=?}。
     *
     * <p>**只写 {@code resource_id} 与记账列，不改 {@code status}**：锁房 ≠ 客人到店
     * （到店事实只由 {@code updateStatusWithVersion} 写 {@code arrived_at}）。
     */
    boolean updateAssignmentWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit);

    /** CAS 改期：单条 {@code UPDATE ... SET start_at=?, end_at=?, version=version+1, updated_* WHERE id=? AND version=?}。 */
    boolean updateScheduleWithVersion(Reservation reservation, int expectedVersion, WriteAudit audit);

    /**
     * 落库结果：业务聚合 + 记账快照。
     *
     * <p>审计四列是记账信息、不进领域模型（standards/10），但响应契约与兼容契约都要求它们，
     * 因此端口把两者分开交给上层。
     */
    record Persisted<T>(T aggregate, AuditSnapshot audit) {
    }

    /**
     * 记账快照（四个审计列的值）。
     *
     * @param createdBy 创建人
     * @param createdAt 创建时间
     * @param updatedBy 更新人
     * @param updatedAt 更新时间
     */
    record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    }

    /**
     * 写入记账值：谁（{@code operatorId}）在什么时候（{@code at}）写的。
     *
     * <p>用参数携带而不是让适配器去读租户上下文：适配器不该理解「当前操作人」这种请求域概念，
     * 而且同一个 {@code at} 被写进库、又被响应视图复用，两边的时间才一致。
     *
     * @param operatorId 操作人（无上下文时调用方给 0 = 系统）
     * @param at         本次写入时刻（{@code updated_at}；新增时同时是 {@code created_at}）
     */
    record WriteAudit(Long operatorId, LocalDateTime at) {
    }
}
