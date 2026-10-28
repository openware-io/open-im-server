package io.openware.platform.order.domain.reservation.model;

import java.time.LocalDateTime;

/**
 * 预约聚合根（领域模型）：**预约对象 = 房型**（{@code room_type_id}），具体包厢
 * （{@code resource_id}）由「到店分配包厢」在到店前后写入。
 *
 * <p><b>字段集只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ktv_reservation}）：
 * id / tenant_id / store_id / reservation_no / idempotency_key / customer_id / business_type /
 * resource_id / room_type_id / start_at / end_at / party_size / contact / status / order_id /
 * arrived_at / version + 四个审计列。持久化对象 {@code ReservationPo} 上的
 * {@code roomTypeCode} / {@code roomTypeName} / {@code resourceName} 标了
 * {@code @TableField(exist = false)}，DDL 里也没有这三列：它们是展示字段，由读路径按
 * {@code room_type_id} / {@code resource_id} 从资源域回填，因此**不在本模型里承载**
 * （它们属于 {@code ReservationView}）。把它们放进写入路径会生成不存在的列引用，MyBatis-Plus
 * 会在**运行时**抛 {@code can not find lambda cache for this property [...]}，{@code test-compile} 看不出来。
 *
 * <p><b>审计字段不在本模型里</b>（{@code created_by / created_at / updated_by / updated_at}）：
 * 它们是记账信息而非业务事实（standards/10「聚合装配与持久化契约」），由适配器读写、
 * 由 {@code ReservationRepository.Persisted} 在聚合之外携带。{@code version} 相反必须留在模型上——
 * 它是业务可见的并发语义，每次状态流转/分配/改期都由它做 CAS 判定。
 *
 * <p><b>不可变记录，没有 setter</b>：字段变更一律返回新实例（{@code with*}），
 * 状态流转集中在 {@link #transitionedTo} / {@link #assignedTo} / {@link #rescheduledTo}。
 *
 * <p><b>时间口径</b>：{@code startAt}/{@code endAt} 是**门店营业本地墙上时间**（+08:00 的
 * {@link LocalDateTime}，见 DDL 列注释），读出来直接展示，不做二次时区换算；
 * {@code arrivedAt} 是**实际到店时间**，与计划时间 {@code startAt} 语义不同。
 *
 * @param id            预约标识；{@code null} 表示尚未落库
 * @param tenantId      租户
 * @param storeId       门店
 * @param reservationNo 预约号（{@code R<yyyyMMdd><当日序号>}，历史行为 {@code R+UUID 前 20 位}）
 * @param idempotencyKey 幂等键（{@code Idempotency-Key} 请求头；租户内唯一，{@code null} = 调用方未提供）
 * @param customerId    会员（{@code cst_customer}）
 * @param businessType  业态
 * @param resourceId    到店分配后的实际包厢（{@code res_resource.id}）；房型驱动的新预约分配前为 {@code null}
 * @param roomTypeId    预约房型（{@code res_room_type.id}）；历史预约（只有 {@code resourceId}）为 {@code null}
 * @param startAt       预约时段开始（门店本地墙上时间）
 * @param endAt         预约时段结束（门店本地墙上时间）
 * @param partySize     预约人数
 * @param contact       联系人（「姓名 手机号」；**绝不进审计详情**）
 * @param status        状态机取值
 * @param orderId       到店开台后产生的统一订单；未开台为 {@code null}
 * @param arrivedAt     实际到店时间；未到店为 {@code null}
 * @param version       乐观锁版本号（CAS 判定用，每次成功写入 +1）
 */
public record Reservation(
    Long id,
    Long tenantId,
    Long storeId,
    String reservationNo,
    String idempotencyKey,
    Long customerId,
    String businessType,
    Long resourceId,
    Long roomTypeId,
    LocalDateTime startAt,
    LocalDateTime endAt,
    Integer partySize,
    String contact,
    ReservationStatus status,
    Long orderId,
    LocalDateTime arrivedAt,
    Integer version) {

    /** 版本号（为空视为 0）。 */
    public int currentVersion() {
        return version == null ? 0 : version;
    }

    /** 是否已落库（{@code id == null} 即尚未落库）。 */
    public boolean persisted() {
        return id != null;
    }

    /** 人数（为空视为 1：DDL 默认值，开台写会话时不会再判空）。 */
    public int partySizeOrDefault() {
        return partySize == null ? 1 : partySize;
    }

    /** 是否到店前（可取消/可改期/可标未到店）。 */
    public boolean preArrival() {
        return status != null && status.preArrival();
    }

    /** 是否仍占用预约名额与时段。 */
    public boolean occupiesSlot() {
        return status != null && status.occupiesSlot();
    }

    /** 是否可开台。 */
    public boolean openTableEligible() {
        return status != null && status.openTableEligible();
    }

    /** 是否已到店或已开台（两者都必须改走「取消订单」）。 */
    public boolean arrivedOrConverted() {
        return status != null && status.arrivedOrConverted();
    }

    /**
     * 新建预约（尚未落库）：只写房型，**不写具体包厢**（{@code resourceId = null}），状态 PENDING、版本 0。
     *
     * <p>「预约就锁房」会把「已预订」的包厢误算成「客人已到店」，具体包厢只能由「到店分配包厢」写入。
     */
    public static Reservation draft(Long tenantId, Long storeId, String reservationNo, String idempotencyKey,
                                    Long customerId, String businessType, Long roomTypeId,
                                    LocalDateTime startAt, LocalDateTime endAt, Integer partySize, String contact) {
        return new Reservation(null, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            null, roomTypeId, startAt, endAt, partySize == null ? 1 : partySize, contact,
            ReservationStatus.PENDING, null, null, 0);
    }

    /** 落库后回填主键（返回新实例）。 */
    public Reservation withId(Long newId) {
        return new Reservation(newId, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            resourceId, roomTypeId, startAt, endAt, partySize, contact, status, orderId, arrivedAt, version);
    }

    /**
     * 状态流转（返回新实例，版本 +1）。
     *
     * <p>{@code newOrderId} / {@code newArrivedAt} 为 {@code null} 表示**保持原值**而不是清空：
     * 取消/未到店走的是「不改订单、不改到店时间」，把它们写成 {@code null} 会把已开台的
     * {@code order_id} 抹掉（那正是「预约取消了、订单还在计时」的悬挂来源）。
     */
    public Reservation transitionedTo(ReservationStatus target, Long newOrderId, LocalDateTime newArrivedAt) {
        return new Reservation(id, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            resourceId, roomTypeId, startAt, endAt, partySize, contact, target,
            newOrderId == null ? orderId : newOrderId,
            newArrivedAt == null ? arrivedAt : newArrivedAt,
            currentVersion() + 1);
    }

    /**
     * 到店分配具体包厢（返回新实例，版本 +1）：**只改 {@code resource_id}，状态不变**。
     *
     * <p>锁房 ≠ 客人到店：运营提前指定包厢（备房/排班）不能把预约标成「客户已到店」，
     * 到店事实只由到店登记或到店开台写入 {@code arrived_at}。
     */
    public Reservation assignedTo(Long newResourceId) {
        return new Reservation(id, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            newResourceId, roomTypeId, startAt, endAt, partySize, contact, status, orderId, arrivedAt,
            currentVersion() + 1);
    }

    /** 改期（返回新实例，版本 +1）：只改时段，状态与包厢分配保持不变。 */
    public Reservation rescheduledTo(LocalDateTime newStartAt, LocalDateTime newEndAt) {
        return new Reservation(id, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            resourceId, roomTypeId, newStartAt, newEndAt, partySize, contact, status, orderId, arrivedAt,
            currentVersion() + 1);
    }
}
