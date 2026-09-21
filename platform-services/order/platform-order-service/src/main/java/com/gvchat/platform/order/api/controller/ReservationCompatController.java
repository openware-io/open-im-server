package com.gvchat.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.ReservationMapper;
import com.gvchat.platform.order.infra.persistence.po.ReservationPo;
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
 */
@RestController
@RequestMapping("/reservations")
public class ReservationCompatController {
    private final ReservationMapper reservationMapper;

    public ReservationCompatController(ReservationMapper reservationMapper) {
        this.reservationMapper = reservationMapper;
    }

    /** 兼容旧「我的预约详情」读路径：/reservations/me/{orderNo}（旧 orderNo 映射为 SaaS reservationNo）。 */
    @GetMapping("/me/{reservationNo}")
    public Map<String, Object> getMine(@PathVariable String reservationNo) {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        ReservationPo po = reservationMapper.selectOne(new LambdaQueryWrapper<ReservationPo>()
                .eq(ReservationPo::getTenantId, tenantId)
                .eq(ReservationPo::getReservationNo, reservationNo)
                .last("LIMIT 1"));
        if (po == null) {
            throw new ApiException(404, "RESERVATION_NOT_FOUND", "预约不存在");
        }
        return toLegacyDetail(po);
    }

    /** SaaS 预约 → 旧 C 端字段（orderNo/status 数值编码等）。 */
    private Map<String, Object> toLegacyDetail(ReservationPo po) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", po.getReservationNo());
        result.put("status", legacyStatusCode(po.getStatus()));
        result.put("statusText", po.getStatus());
        result.put("reserveDate", po.getStartAt() == null ? null : po.getStartAt().toLocalDate().toString());
        result.put("reserveTimePeriod", po.getStartAt() == null ? null : po.getStartAt().toLocalTime().toString());
        result.put("contactName", po.getContact() == null ? null : splitContact(po.getContact())[0]);
        result.put("contactPhone", po.getContact() == null ? null : splitContact(po.getContact())[1]);
        result.put("personNum", po.getPartySize());
        result.put("createdAt", po.getCreatedAt());
        result.put("updatedAt", po.getUpdatedAt());
        return result;
    }

    /** "姓名 手机号" → [姓名, 手机号]。 */
    private static String[] splitContact(String contact) {
        if (contact == null || contact.isBlank()) return new String[]{null, null};
        int idx = contact.indexOf(' ');
        if (idx < 0) return new String[]{contact, null};
        return new String[]{contact.substring(0, idx), contact.substring(idx + 1).trim()};
    }

    /** SaaS 状态 → 旧 C 端 apiCode（1 待核销 / 2 已确认 / 3 已到店 / 4 已取消 / 5 未到店）。 */
    private int legacyStatusCode(String status) {
        if (status == null) return 0;
        return switch (status) {
            case "PENDING" -> 1;
            case "CONFIRMED" -> 2;
            case "ARRIVED", "CONVERTED" -> 3;
            case "CANCELLED" -> 4;
            case "NO_SHOW" -> 5;
            default -> 0;
        };
    }
}
