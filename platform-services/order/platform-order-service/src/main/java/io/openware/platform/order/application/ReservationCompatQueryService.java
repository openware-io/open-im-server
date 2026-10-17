package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.AuditSnapshot;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.Persisted;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端旧预约兼容读路径（E-MIG Switch 阶段启用）。
 *
 * <p>旧契约 {@code GET /api/v1/reservations/**} → 本服务 {@code /reservations/**}；
 * 按签名上下文的租户限定查询，并把 SaaS 预约映射为旧 C 端字段。
 *
 * <p><b>持久化边界</b>：旧契约里的 {@code createdAt}/{@code updatedAt} 是记账列（不进领域模型），
 * 因此从端口返回的 {@link Persisted#audit()} 取，而不是把审计字段搬回聚合。
 */
@Service
@RequiredArgsConstructor
public class ReservationCompatQueryService {
  private final ReservationRepository reservationRepository;

  @Transactional(readOnly = true)
  public LegacyReservationDetail getMine(String reservationNo) {
    Long tenantId = TenantContextHolder.tenantIdOrNull();
    if (tenantId == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    Persisted<Reservation> row = reservationRepository.findByReservationNo(tenantId, reservationNo)
        .orElseThrow(() -> new ApiException(404, "RESERVATION_NOT_FOUND", "预约不存在"));
    return toLegacyDetail(row.aggregate(), row.audit());
  }

  /** SaaS 预约 → 旧 C 端字段（orderNo/status 数值编码等）。 */
  private static LegacyReservationDetail toLegacyDetail(Reservation reservation, AuditSnapshot audit) {
    return new LegacyReservationDetail(
        reservation.reservationNo(),
        legacyStatusCode(reservation),
        statusName(reservation),
        reservation.startAt() == null ? null : reservation.startAt().toLocalDate().toString(),
        reservation.startAt() == null ? null : reservation.startAt().toLocalTime().toString(),
        reservation.contact() == null ? null : splitContact(reservation.contact())[0],
        reservation.contact() == null ? null : splitContact(reservation.contact())[1],
        reservation.partySize(),
        audit == null ? null : audit.createdAt(),
        audit == null ? null : audit.updatedAt());
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
  private static int legacyStatusCode(Reservation reservation) {
    if (reservation.status() == null) return 0;
    return switch (reservation.status()) {
      case PENDING -> 1;
      case CONFIRMED -> 2;
      case ARRIVED, CONVERTED -> 3;
      case CANCELLED -> 4;
      case NO_SHOW -> 5;
    };
  }

  /** 旧 C 端预约详情字段（控制器再映射为旧契约的 Map 形状）。 */
  public record LegacyReservationDetail(String orderNo, int status, String statusText, String reserveDate,
                                        String reserveTimePeriod, String contactName, String contactPhone,
                                        Integer personNum, LocalDateTime createdAt, LocalDateTime updatedAt) {
  }
}
