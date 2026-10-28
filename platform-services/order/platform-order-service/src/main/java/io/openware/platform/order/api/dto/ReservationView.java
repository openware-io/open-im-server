package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.AuditSnapshot;
import java.time.LocalDateTime;

/**
 * 预约一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code ReservationPo}（JSON 字段名一个不改）</b>：
 * {@code /business/reservations/**}、{@code /me/reservations}、{@code /api/v1/reservations/**}
 * 的响应体早已是对外契约（见 {@code docs/contracts/openapi/platform-order-service.json}
 * 的 {@code ReservationPo} schema，以及 {@code scripts/verify/verify-openchat-ktv-closure.ps1}
 * 按 {@code id}/{@code status}/{@code version}/{@code resourceId} 断言的闭环脚本）。
 *
 * <p>其中三个字段**不是数据库列**（原 PO 上标 {@code @TableField(exist = false)}，
 * DDL 的 {@code ktv_reservation} 里也没有）：
 * <ul>
 *   <li>{@link #roomTypeCode} / {@link #roomTypeName}：按 {@code room_type_id} 从资源域房型字典回填；</li>
 *   <li>{@link #resourceName}：按 {@code resource_id} 回填（历史预约只有包厢、没有房型时靠它展示旧包厢）。</li>
 * </ul>
 * 资源域不可达时只丢这三个字段（降级为 {@code null}），不推翻预约本身。
 * 它们绝不能进入任何列引用——对 {@code exist = false} 的字段生成列引用会让 MyBatis-Plus 在运行时抛
 * {@code can not find lambda cache for this property [...]}（{@code test-compile} 看不出来）。
 *
 * <p>审计四列不在领域模型里（记账信息，不是业务事实，见 standards/10），但响应契约要求
 * 这四个字段名继续存在，因此视图在装配期从端口返回的 {@link AuditSnapshot} 取它们的值。
 *
 * @param id             预约标识
 * @param tenantId       租户
 * @param storeId        门店
 * @param reservationNo  预约号
 * @param idempotencyKey 幂等键
 * @param customerId     会员
 * @param businessType   业态
 * @param resourceId     实际包厢（到店分配后写入）
 * @param roomTypeId     预约房型
 * @param startAt        预约时段开始（门店本地墙上时间）
 * @param endAt          预约时段结束（门店本地墙上时间）
 * @param partySize      人数
 * @param contact        联系人（「姓名 手机号」）
 * @param status         PENDING / CONFIRMED / ARRIVED / CANCELLED / NO_SHOW / CONVERTED
 * @param orderId        开台产生的订单
 * @param arrivedAt      实际到店时间
 * @param version        乐观锁版本号（客户端确认/改期时回传）
 * @param roomTypeCode   非列：房型编码（读路径回填，不可达时 {@code null}）
 * @param roomTypeName   非列：房型名称（同上）
 * @param resourceName   非列：包厢名称（同上）
 */
public record ReservationView(
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
    String status,
    Long orderId,
    LocalDateTime arrivedAt,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt,
    String roomTypeCode,
    String roomTypeName,
    String resourceName) {

    /**
     * 由领域聚合 + 记账快照构造（三个展示字段为未回填状态，由调用方按需补齐）。
     *
     * @param audit 该行落库后的记账快照；{@code null} 表示没有可读的记账值（此时四列为 {@code null}）
     */
    public static ReservationView from(Reservation reservation, AuditSnapshot audit) {
        if (reservation == null) {
            return null;
        }
        return new ReservationView(reservation.id(), reservation.tenantId(), reservation.storeId(),
            reservation.reservationNo(), reservation.idempotencyKey(), reservation.customerId(),
            reservation.businessType(), reservation.resourceId(), reservation.roomTypeId(), reservation.startAt(),
            reservation.endAt(), reservation.partySize(), reservation.contact(),
            reservation.status() == null ? null : reservation.status().name(), reservation.orderId(),
            reservation.arrivedAt(), reservation.version(),
            audit == null ? null : audit.createdBy(),
            audit == null ? null : audit.createdAt(),
            audit == null ? null : audit.updatedBy(),
            audit == null ? null : audit.updatedAt(),
            null, null, null);
    }

    /** 回填房型编码/名称与包厢名称（读路径展示字段；返回新实例）。 */
    public ReservationView withDisplay(String newRoomTypeCode, String newRoomTypeName, String newResourceName) {
        return new ReservationView(id, tenantId, storeId, reservationNo, idempotencyKey, customerId, businessType,
            resourceId, roomTypeId, startAt, endAt, partySize, contact, status, orderId, arrivedAt, version,
            createdBy, createdAt, updatedBy, updatedAt, newRoomTypeCode, newRoomTypeName, newResourceName);
    }

    /** 只回填房型编码/名称（包厢名保持原值）。 */
    public ReservationView withRoomType(String newRoomTypeCode, String newRoomTypeName) {
        return withDisplay(newRoomTypeCode, newRoomTypeName, resourceName);
    }

    /** 只回填包厢名（房型字段保持原值）。 */
    public ReservationView withResourceName(String newResourceName) {
        return withDisplay(roomTypeCode, roomTypeName, newResourceName);
    }
}
