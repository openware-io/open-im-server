package com.gvchat.platform.order.api.controller;

import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.platform.order.application.PendingApprovalApplicationService;
import com.gvchat.platform.order.application.dto.PendingApprovalView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「客户待确认加项」提醒接口（P0 提醒链路的数据源）。
 *
 * <p>外部路径 {@code /api/v1/business/orders/pending-approval}（Gateway StripPrefix=2 →
 * {@code /business/orders/pending-approval}）。
 *
 * <p><b>路径为什么挂在 /business/orders 下</b>：网关对 {@code /api/v1/business/**} 是**显式白名单**路由
 * （见 {@code gateways/gateway/src/main/resources/application.yml}），只放行 {@code /business/orders/**}、
 * {@code /business/ktv/**}、{@code /business/reservations/**} 等既有前缀；新前缀（如 {@code /business/order-items}）
 * 不在白名单里会直接 404。挂在 {@code /business/orders/} 下同时避免与 {@code GET /business/orders/{id}} 之类的
 * 变量路径冲突（本服务没有该端点，且字面量路径优先）。
 *
 * <p><b>为什么单独一个端点</b>：客户自助加项落 {@code PENDING_APPROVAL}，需要门店确认/拒绝；
 * 此前只有「点单/加项」弹窗里按订单才能看到，后台角标、收银台卡片、App 都没有提示。
 * 后台/App/B 端为了「实时」都会短轮询，因此这里给出**一次拿全**的聚合视图（按门店），
 * 而不是让每个客户端各自扫订单 + 明细（那是 N+1，且各端计数口径容易漂移）。
 *
 * <p><b>授权</b>：与确认/拒绝同码 {@code order.add_item} —— 能看到提醒的会话必须能处理它，
 * 避免「看得到点不动」。消费者会话没有该权限码，拿不到门店维度的待确认列表（不放开跨客户读取）。
 *
 * <p><b>实时性与一致性</b>：服务端读走进程内 TTL 缓存（默认 3s），写路径（客户提交 / 确认 / 拒绝）
 * 提交后立即失效；{@code revision} 字段供客户端「未变化则跳过重渲染」。未来接入消息中心后，
 * 本端点保留为兜底快照，客户端改为订阅推送即可，无需改口径。
 */
@RestController
@RequestMapping("/business/orders")
public class PendingApprovalController {

    private final PendingApprovalApplicationService pendingApprovalService;

    public PendingApprovalController(PendingApprovalApplicationService pendingApprovalService) {
        this.pendingApprovalService = pendingApprovalService;
    }

    /** 当前门店待确认加项（按订单分组，含计数/金额/revision）。 */
    @GetMapping("/pending-approval")
    public PendingApprovalView pendingApproval() {
        PermissionGuard.require("order.add_item");
        return pendingApprovalService.pendingForCurrentStore();
    }
}
