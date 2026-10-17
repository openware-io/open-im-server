package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.ReservationCompatQueryService;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.AuditSnapshot;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository.Persisted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 端旧预约契约兼容读路径：{@code /reservations/me/{reservationNo}}。
 *
 * <p>迁移后本控制器只依赖领域端口 {@link ReservationRepository}：旧契约里的
 * {@code createdAt}/{@code updatedAt} 是记账列（不进领域模型），断言它们是**从
 * {@link Persisted#audit()} 取的**而不是从聚合上取的——聚合上没有这两个字段。
 */
class ReservationCompatControllerTest {

    private static final Long TENANT_ID = 7L;

    private ReservationRepository reservationRepository;
    private ReservationCompatController controller;

    @BeforeEach
    void setUp() {
        reservationRepository = mock(ReservationRepository.class);
        controller = new ReservationCompatController(new ReservationCompatQueryService(reservationRepository));
        TenantContextHolder.set(new TenantContext(TENANT_ID, 8L, 1001L, 42L, 1, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** 旧字段映射：orderNo/status 数值编码/联系人拆分/人数/记账时间。 */
    @Test
    void getMineMapsSaaSToLegacyFields() {
        when(reservationRepository.findByReservationNo(TENANT_ID, "R202609190001"))
                .thenReturn(Optional.of(persisted(ReservationStatus.CONFIRMED)));

        Map<String, Object> detail = controller.getMine("R202609190001");

        assertEquals("R202609190001", detail.get("orderNo"));
        assertEquals(2, detail.get("status"), "CONFIRMED → 旧 apiCode 2");
        assertEquals("CONFIRMED", detail.get("statusText"));
        assertEquals("2026-09-18", detail.get("reserveDate"));
        assertEquals("20:00", detail.get("reserveTimePeriod"));
        assertEquals("张三", detail.get("contactName"));
        assertEquals("13800138000", detail.get("contactPhone"));
        assertEquals(4, detail.get("personNum"));
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 0), detail.get("createdAt"),
                "记账时间取自端口的审计快照（领域模型不承载审计列）");
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 30), detail.get("updatedAt"));
        verify(reservationRepository).findByReservationNo(TENANT_ID, "R202609190001");
    }

    /** 旧 apiCode 的其余分支：已到店/已开台都映射到 3，取消 4、未到店 5。 */
    @Test
    void legacyStatusCodesCoverEveryStateOfTheClosedSet() {
        assertEquals(1, statusOf(ReservationStatus.PENDING));
        assertEquals(2, statusOf(ReservationStatus.CONFIRMED));
        assertEquals(3, statusOf(ReservationStatus.ARRIVED));
        assertEquals(3, statusOf(ReservationStatus.CONVERTED), "已开台按旧契约同样算「已到店」");
        assertEquals(4, statusOf(ReservationStatus.CANCELLED));
        assertEquals(5, statusOf(ReservationStatus.NO_SHOW));
    }

    /** 没有联系人时不拆名字（旧契约两列都为 null，不得出现空串）。 */
    @Test
    void getMineToleratesMissingContact() {
        Reservation withoutContact = new Reservation(9L, TENANT_ID, 1001L, "R202609190001", null, 99L, "KTV",
                null, 55L, LocalDateTime.of(2026, 9, 18, 20, 0), LocalDateTime.of(2026, 9, 18, 22, 0), 4, null,
                ReservationStatus.PENDING, null, null, 0);
        when(reservationRepository.findByReservationNo(TENANT_ID, "R202609190001"))
                .thenReturn(Optional.of(new Persisted<>(withoutContact, null)));

        Map<String, Object> detail = controller.getMine("R202609190001");

        assertNull(detail.get("contactName"));
        assertNull(detail.get("contactPhone"));
        assertNull(detail.get("createdAt"), "没有审计快照时字段为 null，不假装有值");
    }

    /** 预约不存在 → 404（跨租户由租户拦截器过滤成同一语义）。 */
    @Test
    void getMineReturns404WhenAbsent() {
        when(reservationRepository.findByReservationNo(any(), any())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> controller.getMine("R-NOPE"));

        assertEquals(404, ex.getStatus());
        assertEquals("RESERVATION_NOT_FOUND", ex.getCode());
    }

    /** 缺少租户上下文 → 401，且不查库。 */
    @Test
    void getMineRequiresTenantContext() {
        TenantContextHolder.clear();

        ApiException ex = assertThrows(ApiException.class, () -> controller.getMine("R202609190001"));

        assertEquals(401, ex.getStatus());
        assertEquals("SAAS_CONTEXT_REQUIRED", ex.getCode());
    }

    private int statusOf(ReservationStatus status) {
        when(reservationRepository.findByReservationNo(TENANT_ID, "R202609190001"))
                .thenReturn(Optional.of(persisted(status)));
        return (int) controller.getMine("R202609190001").get("status");
    }

    private static Persisted<Reservation> persisted(ReservationStatus status) {
        Reservation reservation = new Reservation(9L, TENANT_ID, 1001L, "R202609190001", null, 99L, "KTV",
                null, 55L, LocalDateTime.of(2026, 9, 18, 20, 0), LocalDateTime.of(2026, 9, 18, 22, 0), 4,
                "张三 13800138000", status, null, null, 0);
        return new Persisted<>(reservation, new AuditSnapshot(1L, LocalDateTime.of(2026, 9, 18, 9, 0),
                1L, LocalDateTime.of(2026, 9, 18, 9, 30)));
    }
}
