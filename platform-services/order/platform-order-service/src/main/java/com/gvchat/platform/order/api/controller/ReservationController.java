package com.gvchat.platform.order.api.controller;

import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import com.gvchat.platform.order.application.ReservationApplicationService;
import com.gvchat.platform.order.infra.client.TenantBusinessHoursClient;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.platform.order.infra.persistence.po.ReservationPo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SaaS 预约读写契约入口（SAAS_PLATFORM_05 §5.3）。
 * 外部路径 /api/v1/business/reservations/**（Gateway StripPrefix=2 → /business/reservations/**）。
 * 写路径由 {@link ReservationApplicationService} 承接：状态机 + 乐观锁（version）+ 租户上下文。
 * 读路径（list/get）由应用服务回填房型名/包厢名后返回（租户过滤由 TenantLineInnerInterceptor 自动完成）。
 *
 * <p><b>预约对象 = 房型</b>（docs/renovation/KTV_RESERVATION_ROOM_TYPE.md）：
 * 创建预约传 {@code roomTypeId}（必填），不传具体包厢；具体包厢由「到店分配包厢」写入。
 *
 * <p>时间口径（对应 ord_reservation.start_at/end_at 的列注释，见 V19__ord_reservation_time_comment.sql）：
 * <ul>
 *   <li>入参 {@code startAt}/{@code endAt} 必须是**带时区偏移**的 ISO-8601（如 {@code 2026-09-18T20:00:00+08:00}）；
 *       服务端统一换算到 +08:00 后去掉偏移落库，不做二次时区推断。</li>
 *   <li>出参 {@code startAt}/{@code endAt} 是**门店营业本地墙上时间**（+08:00 的 LocalDateTime，无偏移信息）；
 *       前端直接按本地时钟渲染，不要再做 UTC 转换（列注释此前误写为 UTC，已由 V19 迁移纠正）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/business/reservations")
public class ReservationController {
    private final ReservationApplicationService reservationService;
    private final CustomerLookupMapper customerLookupMapper;

    public ReservationController(ReservationApplicationService reservationService,
                                 CustomerLookupMapper customerLookupMapper) {
        this.reservationService = reservationService;
        this.customerLookupMapper = customerLookupMapper;
    }

    /**
     * 查询预约列表（当前租户，TenantLineInnerInterceptor 自动追加 tenant_id）。
     *
     * <p><b>时间区间</b>：{@code from}/{@code to} 按**预约时段开始时间** {@code start_at} 的闭区间筛选，
     * 统一口径见 {@link TimeRangeParams}：日期形态的 from/to 分别收口到当天起点与当天末尾，
     * 也接受 {@code yyyy-MM-ddTHH:mm:ss}；为空 = 不筛；{@code from > to} → 400 {@code TIME_RANGE_INVALID}。
     */
    @GetMapping
    public List<ReservationPo> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        PermissionGuard.require("reservation.view");
        return reservationService.list(TimeRangeParams.parse(from, to));
    }

    /** 预约详情（当前租户；跨租户由租户拦截器过滤为 404 语义）。 */
    @GetMapping("/{id}")
    public ReservationPo get(@PathVariable Long id) {
        PermissionGuard.require("reservation.view");
        return reservationService.get(id);
    }

    /**
     * 创建预约：POST /business/reservations（PENDING，Idempotency-Key 幂等）。
     * startAt/endAt 需带时区偏移（如 +08:00），服务端归一到门店营业本地时间（+08:00）落库。
     *
     * <p>请求体带 {@code resourceId} 时**显式 400**（{@code RESOURCE_ID_NOT_ALLOWED}）：预约按房型创建，
     * 具体包厢到店后才分配。这里不做静默忽略 —— 否则调用方会误以为它选的包厢已经被预约锁定。
     */
    @PostMapping
    public ReservationPo create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                @RequestBody CreateReservationRequest req) {
        PermissionGuard.require("reservation.create");
        Long tenantId = requireTenant();
        TenantContext context = TenantContextHolder.get();
        Long storeId = context == null ? null : context.storeId();
        if (storeId == null) {
            throw new BusinessException("STORE_CONTEXT_MISSING", "缺少门店上下文");
        }
        if (req.resourceId() != null) {
            throw new ApiException(400, "RESOURCE_ID_NOT_ALLOWED",
                    "预约按房型（roomTypeId）创建，不接受具体包厢 resourceId，具体包厢到店后由门店分配");
        }
        return reservationService.create(tenantId, idempotencyKey, new ReservationApplicationService.CreateReservationCommand(
                req.businessType(), memberId(), storeId, req.roomTypeId(),
                req.startAt(), req.endAt(), req.partySize(), req.contact()));
    }

    /** 确认预约：POST /business/reservations/{id}/confirm（PENDING → CONFIRMED，乐观锁 expectedVersion）。 */
    @PostMapping("/{id}/confirm")
    public ReservationPo confirm(@PathVariable Long id, @RequestBody ConfirmReservationRequest req) {
        PermissionGuard.require("reservation.confirm");
        return reservationService.confirm(id, req.expectedVersion());
    }

    /** 到店：POST /business/reservations/{id}/arrival（CONFIRMED → ARRIVED，写 arrived_at 真实到店时间）。 */
    @PostMapping("/{id}/arrival")
    public ReservationPo arrival(@PathVariable Long id, @RequestBody(required = false) ArrivalReservationRequest req) {
        PermissionGuard.require("reservation.arrival");
        return reservationService.arrival(id, req == null ? null : req.operatorNote());
    }

    /**
     * 标记未到店：POST /business/reservations/{id}/no-show（到店前且已过预约开始时间 → NO_SHOW）。
     *
     * <p>权限复用 {@code reservation.arrival}（与「到店登记/分配包厢」同属履约动作域，
     * 不新增 IAM 基线之外的孤立权限码）；状态与错误码见 {@link ReservationApplicationService#noShow}。
     */
    @PostMapping("/{id}/no-show")
    public ReservationPo noShow(@PathVariable Long id) {
        PermissionGuard.require("reservation.arrival");
        return reservationService.noShow(id);
    }

    /**
     * 到店分配具体包厢：POST /business/reservations/{id}/assign-room（body {@code {resourceId, override}}）。
     *
     * <p>权限复用 {@code reservation.arrival}：分配包厢是到店履约动作，与「到店登记」同权限域，
     * 不新增未被 IAM 基线定义的孤立权限码（同 V17 建开台权限的处理）。
     * 状态与幂等语义见 {@link ReservationApplicationService#assignRoom}。
     */
    /**
     * 生效营业时间：GET /business/reservations/business-hours?storeId=（缺省用上下文门店）。
     *
     * <p>给**所有会创建预约的客户端**用它约束「到店时间」（C 端下单页、B 端/后台代客预约）：
     * 与服务端创建校验同源（同一个租户域配置），因此「界面能给客人选的时段」与「服务端会放行的时段」
     * 永远一致，避免各端各写一份营业时间判断。缺省 18:00 – 次日 05:00。
     *
     * <p>只读、租户上下文内（无权限码）：营业时间不是敏感数据，但下单前的顾客必须能读到它。
     */
    @GetMapping("/business-hours")
    public BusinessHoursResponse businessHours(@RequestParam(required = false) Long storeId) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "TENANT_CONTEXT_MISSING", "缺少租户上下文");
        }
        Long effectiveStoreId = storeId != null ? storeId : context.storeId();
        TenantBusinessHoursClient.BusinessHours hours = reservationService.businessHours(effectiveStoreId);
        return new BusinessHoursResponse(effectiveStoreId, hours.open().toString(), hours.close().toString(),
                hours.source(), hours.crossesMidnight(), hours.allDay(), hours.displayText());
    }

    /**
     * 分配包厢的候选列表：GET /business/reservations/{id}/assignable-rooms。
     *
     * <p>服务端一次算完「该预约可分配给哪些包厢、不可分配的原因是什么」（房态 + 本时段预约冲突），
     * 前端不再自己拼资源列表 —— 那会把房态读失败的降级当成「都可用」。
     * 权限与 {@code assign-room} 一致（{@code reservation.arrival}）。
     */
    @GetMapping("/{id}/assignable-rooms")
    public List<ReservationApplicationService.AssignableRoom> assignableRooms(@PathVariable Long id) {
        PermissionGuard.require("reservation.arrival");
        return reservationService.assignableRooms(id);
    }

    @PostMapping("/{id}/assign-room")
    public ReservationPo assignRoom(@PathVariable Long id, @RequestBody(required = false) AssignRoomRequest req) {
        PermissionGuard.require("reservation.arrival");
        if (req == null || req.resourceId() == null) {
            throw new ApiException(400, "RESOURCE_ID_REQUIRED", "缺少 resourceId");
        }
        return reservationService.assignRoom(id, req.resourceId(), Boolean.TRUE.equals(req.override()));
    }

    /** 到店预约开台：POST /business/reservations/{id}/open-table（ARRIVED/CONFIRMED → 生成订单+会话+计时，回填 order_id）。 */
    @PostMapping("/{id}/open-table")
    public OrderPo openTable(@PathVariable Long id, @RequestBody(required = false) OpenTableReservationRequest req) {
        PermissionGuard.require("ktv.session.operate");
        return reservationService.openTable(id, req == null ? null : req.freeWaitMinutes());
    }

    /**
     * 取消预约：POST /business/reservations/{id}/cancel（到店前 → CANCELLED，body {@code {reason}}）。
     *
     * <p>权限用 {@code reservation.cancel}，**不要**改成 {@code reservation.arrival}：
     * <ul>
     *   <li>{@code reservation.cancel} 在 IAM 基线上（V22__seed_ktv_loop_permissions.sql 明确
     *       {@code POST /business/reservations/{id}/cancel -> reservation.cancel}，并授予
     *       tenant.owner / store.manager），不是孤立权限码；</li>
     *   <li>{@code reservation.arrival} 被 V21 额外授予了 {@code store.cashier}，用它做取消权限
     *       等于把「取消预约」放开给收银员（V22 恰恰显式排除了收银员的取消权），属权限放大。</li>
     * </ul>
     * 原因必填、状态与审计口径见 {@link ReservationApplicationService#cancel}。
     */
    @PostMapping("/{id}/cancel")
    public ReservationPo cancel(@PathVariable Long id, @RequestBody(required = false) CancelReservationRequest req) {
        PermissionGuard.require("reservation.cancel");
        return reservationService.cancel(id, req == null ? null : req.reason());
    }

    private Long requireTenant() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) {
            throw new BusinessException("SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return tenantId;
    }

    private Long memberId() {
        TenantContext context = TenantContextHolder.get();
        if (context == null) {
            throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
        }
        Long memberId = customerLookupMapper.findMemberId(context.tenantId(), context.accountId());
        if (memberId == null) {
            throw new ApiException(409, "MEMBER_INITIALIZATION_REQUIRED", "请先初始化会员资料");
        }
        return memberId;
    }

    /**
     * 创建预约请求体（预约按房型）。
     *
     * @param roomTypeId 预约房型（res_room_type.id，必填）；预约页「选择包厢类型」选中的就是它
     * @param resourceId 具体包厢；**已废弃**——带上会被显式 400 拒绝（RESOURCE_ID_NOT_ALLOWED），
     *                   保留字段只为给出明确错误而不是静默忽略
     * @param startAt 预约开始时间，必须带时区偏移（如 2026-09-18T20:00:00+08:00）；服务端归一到 +08:00 门店本地时间
     * @param endAt   预约结束时间，口径同 startAt，且必须晚于 startAt
     */
    public record CreateReservationRequest(String businessType, Long customerId, Long storeId, Long resourceId,
                                           Long roomTypeId, OffsetDateTime startAt, OffsetDateTime endAt,
                                           Integer partySize, String contact) {}

    /**
     * 分配包厢请求体。
     *
     * @param resourceId 目标包厢（res_resource.id，必须属于该预约的房型与门店）
     * @param override   已分配包厢时是否显式改派；false/null 时换包厢返回 409 RESERVATION_ROOM_ASSIGNED
     */
    public record AssignRoomRequest(Long resourceId, Boolean override) {}

    public record ConfirmReservationRequest(Integer expectedVersion) {}
    public record ArrivalReservationRequest(String operatorNote) {}
    public record OpenTableReservationRequest(Integer freeWaitMinutes) {}
    public record CancelReservationRequest(String reason) {}

    /**
     * 生效营业时间响应（C 端/B 端/后台的「到店时间」选择器据此约束，客户端不各写一份规则）。
     *
     * @param openTime         开始营业时刻（HH:mm，左闭）
     * @param closeTime        结束营业时刻（HH:mm，右开；早于 openTime 表示跨自然日）
     * @param source           STORE/TENANT/DEFAULT：该值来自门店配置、租户默认还是代码缺省
     * @param crossesMidnight  是否跨自然日（如 18:00–05:00）
     * @param allDay           是否全天营业（open == close）
     * @param displayText      展示文案（跨自然日时带「次日」）
     */
    public record BusinessHoursResponse(Long storeId, String openTime, String closeTime, String source,
                                        boolean crossesMidnight, boolean allDay, String displayText) {}
}
