package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.platform.admin.infra.ReservationDomainClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SaaS 后台预约管理 BFF：列表 + 确认/到店/取消/分配包厢，转发到 platform-order-service。
 *
 * <p>预约对象是房型（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md）：后台列表展示房型与时段，
 * 「分配包厢」在客人到店后把预约落到该房型下的具体包厢，再允许开台。
 */
@RestController
@RequestMapping("/admin/reservations")
public class ReservationController {

    private final ReservationDomainClient reservationClient;

    public ReservationController(ReservationDomainClient reservationClient) {
        this.reservationClient = reservationClient;
    }

    /**
     * 预约列表（BFF 转发到 order 域）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**预约时段开始时间** {@code start_at} 的闭区间筛选，
     * 本层只做**参数校验**（与全仓其它列表同一错误码 {@code TIME_RANGE_INVALID}、
     * 同一日期收口口径），校验通过后原样透传给 order 域（域内用同一个
     * {@code io.openware.infrastructure.time.TimeRangeParams} 解析），避免 BFF 与域内两套口径。
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        TimeRangeParams.parse(from, to);
        return reservationClient.list(from, to);
    }

    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmRequest req) {
        return reservationClient.confirm(id, req == null ? null : req.expectedVersion());
    }

    @PostMapping("/{id}/arrival")
    public Map<String, Object> arrival(@PathVariable Long id) {
        return reservationClient.arrival(id);
    }

    /**
     * 分配包厢候选：GET /admin/reservations/{id}/assignable-rooms。
     *
     * <p>候选与「是否可分配、为什么不可分配」由 platform-order-service 一次算完
     * （房态运行态 + 本预约时段的预约冲突），后台弹窗直接用这份数据，不再自己拼资源列表。
     */
    @GetMapping("/{id}/assignable-rooms")
    public List<Map<String, Object>> assignableRooms(@PathVariable Long id) {
        return reservationClient.assignableRooms(id);
    }

    /**
     * 到店分配具体包厢：POST /admin/reservations/{id}/assign-room（body {@code {resourceId, override}}）。
     * 域内校验与审计（reservation.assign_room，含 before/after）由 platform-order-service 完成，
     * 本层只做参数完整性校验并原样转发。
     */
    @PostMapping("/{id}/assign-room")
    public Map<String, Object> assignRoom(@PathVariable Long id, @RequestBody(required = false) AssignRoomRequest req) {
        if (req == null || req.resourceId() == null) {
            throw new ApiException(400, "RESOURCE_ID_REQUIRED", "缺少 resourceId");
        }
        return reservationClient.assignRoom(id, req.resourceId(), Boolean.TRUE.equals(req.override()));
    }

    /**
     * 取消预约：POST /admin/reservations/{id}/cancel（body {@code {reason}}）。
     *
     * <p>原因**必填**：运营代客取消必须在操作日志里留下原因，空白直接 400
     * {@code CANCEL_REASON_REQUIRED}（与域内 platform-order-service 同一错误码与提示，
     * 本层先拦一次是为了不把显然非法的请求打到域内）。
     */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        String reason = req == null ? null : req.reason();
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, "CANCEL_REASON_REQUIRED", "取消预约必须填写原因");
        }
        return reservationClient.cancel(id, reason.trim());
    }

    /** 预约履约开台：ARRIVED/CONFIRMED 预约 → 生成订单 + KTV 包厢会话并回填 order_id。 */
    @PostMapping("/{id}/open-table")
    public Map<String, Object> openTable(@PathVariable Long id) {
        return reservationClient.openTable(id);
    }

    /**
     * 标记未到店：POST /admin/reservations/{id}/no-show（到店前且已过预约开始时间 → NO_SHOW）。
     * 用于超时未到的预约释放包厢预约位（此前该异常分支没有任何入口）；
     * 状态校验与审计 reservation.no_show 由 platform-order-service 完成，本层只转发。
     */
    @PostMapping("/{id}/no-show")
    public Map<String, Object> noShow(@PathVariable Long id) {
        return reservationClient.noShow(id);
    }

    public record CancelRequest(String reason) {}
    public record ConfirmRequest(Integer expectedVersion) {}

    /**
     * 分配包厢请求体。
     *
     * @param resourceId 目标包厢（必须属于该预约的房型与门店）
     * @param override   已分配包厢时是否显式改派；false/null 时换包厢返回 409 RESERVATION_ROOM_ASSIGNED
     */
    public record AssignRoomRequest(Long resourceId, Boolean override) {}
}
