package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.Persisted;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端旧预约兼容读路径（E-MIG Switch 阶段启用）。
 * 旧契约：GET /api/v1/reservations/** → im-order-service /reservations/**（IM 库）。
 * E-MIG Switch 后网关将读路径改指本服务 /reservations/**，按租户上下文限定查询并映射为旧契约字段。
 *
 * <p><b>持久化边界</b>：本控制器只依赖领域端口 {@link ReservationRepository} 读聚合；
 * 旧契约里的 {@code createdAt}/{@code updatedAt} 是记账列（不进领域模型），
 * 因此它们从端口返回的 {@link Persisted#audit()} 取，而不是把审计字段搬回聚合。
 */
@RestController
@RequestMapping("/reservations")
public class ReservationCompatController {
    private final ReservationRepository reservationRepository;

    public ReservationCompatController(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /** 兼容旧「我的预约详情」读路径：/reservations/me/{orderNo}（旧 orderNo 映射为 SaaS reservationNo）。 */
    @GetMapping("/me/{reservationNo}")
    public Map<String, Object> getMine(@PathVariable String reservationNo) {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        Persisted<Reservation> row = reservationRepository.findByReservationNo(tenantId, reservationNo)
                .orElseThrow(() -> new ApiException(404, "RESERVATION_NOT_FOUND", "预约不存在"));
        return toLegacyDetail(row.aggregate(), row.audit());
    }

    /** SaaS 预约 → 旧 C 端字段（orderNo/status 数值编码等）。 */
    private Map<String, Object> toLegacyDetail(Reservation reservation, ReservationRepository.AuditSnapshot audit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", reservation.reservationNo());
        result.put("status", legacyStatusCode(reservation));
        result.put("statusText", statusName(reservation));
        result.put("reserveDate", reservation.startAt() == null ? null : reservation.startAt().toLocalDate().toString());
        result.put("reserveTimePeriod", reservation.startAt() == null ? null : reservation.startAt().toLocalTime().toString());
        result.put("contactName", reservation.contact() == null ? null : splitContact(reservation.contact())[0]);
        result.put("contactPhone", reservation.contact() == null ? null : splitContact(reservation.contact())[1]);
        result.put("personNum", reservation.partySize());
        result.put("createdAt", audit == null ? null : audit.createdAt());
        result.put("updatedAt", audit == null ? null : audit.updatedAt());
        return result;
    }

    /** "姓名 手机号" → [姓名, 手机号]。 */
    private static String[] splitContact(String contact) {
        if (contact == null || contact.isBlank()) return new String[]{null, null};
        int idx = contact.indexOf(' ');
        if (idx < 0) return new String[]{contact, null};
        return new String[]{contact.substring(0, idx), contact.substring(idx + 1).trim()};
    }

    private static String statusName(Reservation reservation) {
        return reservation.status() == null ? null : reservation.status().name();
    }

    /** SaaS 状态 → 旧 C 端 apiCode（1 待核销 / 2 已确认 / 3 已到店 / 4 已取消 / 5 未到店）。 */
    private int legacyStatusCode(Reservation reservation) {
        if (reservation.status() == null) return 0;
        return switch (reservation.status()) {
            case PENDING -> 1;
            case CONFIRMED -> 2;
            case ARRIVED, CONVERTED -> 3;
            case CANCELLED -> 4;
            case NO_SHOW -> 5;
        };
    }
}
