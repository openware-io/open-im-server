package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.ReservationCompatQueryService;
import io.openware.platform.order.application.ReservationCompatQueryService.LegacyReservationDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端旧预约兼容读路径（E-MIG Switch 阶段启用）。
 * 旧契约：GET /api/v1/reservations/** → im-order-service /reservations/**（IM 库）。
 * E-MIG Switch 后网关将读路径改指本服务 /reservations/**。
 *
 * <p>租户上下文、查询与旧字段映射在 {@link ReservationCompatQueryService}；
 * 本类只把结果映射为旧契约的 Map 形状。
 */
@RestController
@RequestMapping("/reservations")
public class ReservationCompatController {
    private final ReservationCompatQueryService reservationCompatService;

    public ReservationCompatController(ReservationCompatQueryService reservationCompatService) {
        this.reservationCompatService = reservationCompatService;
    }

    /** 兼容旧「我的预约详情」读路径：/reservations/me/{orderNo}（旧 orderNo 映射为 SaaS reservationNo）。 */
    @GetMapping("/me/{reservationNo}")
    public Map<String, Object> getMine(@PathVariable String reservationNo) {
        return toLegacyMap(reservationCompatService.getMine(reservationNo));
    }

    private static Map<String, Object> toLegacyMap(LegacyReservationDetail detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", detail.orderNo());
        result.put("status", detail.status());
        result.put("statusText", detail.statusText());
        result.put("reserveDate", detail.reserveDate());
        result.put("reserveTimePeriod", detail.reserveTimePeriod());
        result.put("contactName", detail.contactName());
        result.put("contactPhone", detail.contactPhone());
        result.put("personNum", detail.personNum());
        result.put("createdAt", detail.createdAt());
        result.put("updatedAt", detail.updatedAt());
        return result;
    }
}
