package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.KtvServerSessionView;
import io.openware.platform.order.api.dto.KtvSessionView;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderApplicationService;
import io.openware.platform.order.application.OrderApplicationService.CreateOrderCommand;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.application.VoidRuleApplicationService;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.model.KtvServerSession;
import io.openware.platform.order.domain.order.model.Order;
import java.util.List;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.*;

/**
 * 订单端点：订单读写、KTV 会话操作、取消/作废。
 *
 * <p>订单列表/详情/创建/确认/挂单/解挂的授权、会话投影、实时合计、幂等与审计在
 * {@link OrderApplicationService}；KTV 会话操作委托 {@link KtvSessionApplicationService} /
 * {@link KtvServerSessionApplicationService}；取消/作废委托
 * {@link OrderCancellationApplicationService}。本类只做协议转换与端点权限守卫。
 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/business")
public class OrderController {

    private final OrderApplicationService orderService;
    private final KtvSessionApplicationService ktvSessionService;
    private final KtvServerSessionApplicationService ktvServerSessionService;
    private final OrderCancellationApplicationService orderCancellationService;
    private final VoidRuleApplicationService voidRuleService;

    public OrderController(OrderApplicationService orderService,
                           KtvSessionApplicationService ktvSessionService,
                           KtvServerSessionApplicationService ktvServerSessionService,
                           OrderCancellationApplicationService orderCancellationService,
                           VoidRuleApplicationService voidRuleService) {
        this.orderService = orderService;
        this.ktvSessionService = ktvSessionService;
        this.ktvServerSessionService = ktvServerSessionService;
        this.orderCancellationService = orderCancellationService;
        this.voidRuleService = voidRuleService;
    }

    /** 查询订单列表（当前租户，按创建时间倒序，可选时间闭区间与会员收窄）。 */
    @GetMapping("/orders")
    public List<OrderView> list(@RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        return orderService.list(from, to);
    }

    /** 订单详情。 */
    @GetMapping("/orders/{id}")
    public OrderView detail(@PathVariable Long id) {
        return orderService.detail(id);
    }

    /** 按订单查 KTV 会话（点单/结台/续台/转台需 sessionId）。 */
    @GetMapping("/orders/{id}/session")
    public KtvSessionView session(@PathVariable Long id) {
        return liveView(ktvSessionService.findByOrderId(id));
    }

    /** 快速开台：创建 KTV 订单 + 包厢会话。 */
    @PostMapping("/orders")
    public OrderView createOrder(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody CreateOrderRequest req) {
        return orderService.createOrder(idempotencyKey, new CreateOrderCommand(
                req.tenantId(), req.organizationId(), req.storeId(), req.businessType(),
                req.currencyCode(), req.resourceId()));
    }

    /** 确认订单：交易状态 DRAFT → PENDING_PAYMENT（进入结算；兼容列写 WAITING_SETTLEMENT）。 */
    @PostMapping("/orders/{id}/confirm")
    public OrderView confirm(@PathVariable Long id) {
        return orderService.confirm(id);
    }

    /** 开台：可选登记到店人数（partySize）。 */
    @PostMapping("/ktv/sessions/{id}/open")
    public KtvSessionView open(@PathVariable Long id, @RequestBody OpenRequest req) {
        PermissionGuard.require("ktv.session.open");
        // 写路径不下发实时估算（与迁移前一致：开台响应里的计时/预估字段为空）。
        return KtvSessionView.from(ktvSessionService.open(id, req == null ? null : req.freeWaitMinutes(),
                req == null ? null : req.partySize()));
    }

    @PostMapping("/ktv/sessions/{id}/pause")
    public KtvSessionView pause(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return KtvSessionView.from(ktvSessionService.pause(id));
    }

    @PostMapping("/ktv/sessions/{id}/resume")
    public KtvSessionView resume(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return KtvSessionView.from(ktvSessionService.resume(id));
    }

    @PostMapping("/ktv/sessions/{id}/close")
    public KtvSessionView close(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.operate");
        return KtvSessionView.from(ktvSessionService.close(id));
    }

    @PostMapping("/ktv/sessions/{id}/cancel")
    public KtvSessionView cancel(@PathVariable Long id) {
        PermissionGuard.require("ktv.session.open");
        return KtvSessionView.from(ktvSessionService.cancel(id));
    }

    /** 点服务人员：POST /business/orders/{id}/servers → ORDERED（计时开始）。 */
    @PostMapping("/orders/{id}/servers")
    public KtvServerSessionView orderServer(@PathVariable Long id, @RequestBody OrderServerRequest req) {
        PermissionGuard.require("ktv.server.order");
        KtvServerSession ordered = ktvServerSessionService.order(requireContext().tenantId(), id,
                req.ktvSessionId(), req.serverResourceId(), req.catalogItemId());
        return KtvServerSessionView.from(ordered);
    }

    /** 挂单：开台中（交易状态 DRAFT + 会话 OPEN/PAUSED）的操作标记，不结台、不改交易状态。 */
    @PostMapping("/orders/{id}/hold")
    public OrderView hold(@PathVariable Long id, @RequestBody HoldRequest req) {
        return orderService.hold(id, req.reason());
    }

    /** 解除挂单。 */
    @PostMapping("/orders/{id}/unhold")
    public OrderView unhold(@PathVariable Long id) {
        return orderService.unhold(id);
    }

    /** 转台：换包厢，计时继承（累计）不重置。 */
    @PostMapping("/orders/{id}/transfer")
    public KtvSessionView transfer(@PathVariable Long id, @RequestBody TransferRequest req) {
        PermissionGuard.require("order.transfer");
        Order order = requireOrder(id);
        KtvSession session = ktvSessionService.findByOrderId(order.id());
        return KtvSessionView.from(ktvSessionService.transfer(session.id(), req.targetResourceId()));
    }

    /** 取消订单（运营代客取消）：权限沿用 {@code order.void}。 */
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancelOrder(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        PermissionGuard.require("order.void");
        return OrderView.from(orderCancellationService.cancel(id, req == null ? null : req.reason()));
    }

    /** 作废：交易状态 → VOIDED（与用户取消 CANCELLED 分开统计）；与取消共用状态校验/已收款拦截/包厢释放。 */
    @PostMapping("/orders/{id}/void")
    public OrderView voidOrder(@PathVariable Long id, @RequestBody(required = false) VoidRequest req) {
        PermissionGuard.require("order.void");
        requireApprovalWhenConfigured(id);
        return OrderView.from(orderCancellationService.voidOrder(id, req == null ? null : req.reason()));
    }

    private void requireApprovalWhenConfigured(Long id) {
        if (voidRuleService == null) return;
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() == null) return;
        Order order = orderCancellationService.requireOrder(id);
        if (voidRuleService.isApprovalRequired(context.tenantId(), order.businessType(), order.storeId())) {
            throw new ApiException(409, "VOID_APPROVAL_REQUIRED", "当前业态/门店要求先提交作废审批");
        }
    }

    /** 暂停修正：店长/财务修正 paused_seconds。 */
    @PostMapping("/ktv/sessions/{id}/correct-pause")
    public KtvSessionView correctPause(@PathVariable Long id, @RequestBody CorrectPauseRequest req) {
        PermissionGuard.require("ktv.session.correct_pause");
        return KtvSessionView.from(ktvSessionService.correctPause(id, req.pausedSeconds()));
    }

    /** 会话的对外视图；额外带上实时计时与实时房费。 */
    private KtvSessionView liveView(KtvSession session) {
        KtvSessionApplicationService.LiveEstimate live = ktvSessionService.liveEstimate(session, null);
        return live == null
                ? KtvSessionView.from(session)
                : KtvSessionView.from(session, live.elapsedSeconds(), live.estimatedRoomFee());
    }

    /** 订单查询（404 不存在 / 403 跨租户）：口径集中在取消服务，避免多处各写一套。 */
    private Order requireOrder(Long id) {
        return orderCancellationService.requireOrder(id);
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.organizationId() == null || context.storeId() == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/组织/门店上下文");
        }
        return context;
    }

    public record CreateOrderRequest(Long tenantId, Long organizationId, Long storeId, String businessType,
                                     String currencyCode, Long resourceId) {}
    public record OpenRequest(Integer freeWaitMinutes, Integer partySize) {}
    public record OrderServerRequest(Long tenantId, Long ktvSessionId, Long serverResourceId, Long catalogItemId) {}
    public record HoldRequest(String reason) {}
    public record TransferRequest(Long targetResourceId) {}
    public record VoidRequest(String reason) {}
    /** 取消订单请求体：{@code reason} 必填（空白 → 400 CANCEL_REASON_REQUIRED），≤255 字符。 */
    public record CancelRequest(String reason) {}
    public record CorrectPauseRequest(Integer pausedSeconds) {}
}
