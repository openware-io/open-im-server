package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.BillApplicationService;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户消费账单（只读）：GET /business/orders/{id}/bill，分级明细（KTV_BUSINESS_01 §9）。
 *
 * <p><b>授权口径（2026-09-19 修复越权）</b>：旧实现既没有权限码、也没有订单归属校验 ——
 * 任何登录的消费者只要换一个订单 ID 就能读到同租户**他人订单**的账单（IDOR），
 * 而 C 端「查看账单」走的正是这个端点。现在与「加服务项」同一条规则：
 * <ul>
 *   <li>持有 {@code order.view}（收银员/店长/财务/服务人员等 B 端角色）→ 行为完全不变；</li>
 *   <li>未持有（A380 C 端消费者会话）→ 只允许**本人**订单
 *       （签名上下文 accountId → cst_member.id → {@code ord_order.customer_id}，
 *       与 {@code GET /me/orders}、加项自助路径同一口径），他人订单回 403 {@code ORDER_SCOPE_DENIED}；
 *       解析不出会员档案时保持既有 403 {@code PERMISSION_DENIED} 文案，不新增可探测的信息面。</li>
 * </ul>
 */
@RestController
@RequestMapping("/business/orders/{orderId}")
public class OrderBillController {

    /** 商户侧「查看订单/账单」权限码（与 OrderItemController 的读路径同码）。 */
    private static final String PERMISSION_VIEW_ORDER = "order.view";

    private final BillApplicationService billService;
    private final OrderMapper orderMapper;
    private final CustomerLookupMapper customerLookupMapper;

    /** 兼容既有装配（单测/无归属校验依赖时只走商户权限码路径）。 */
    public OrderBillController(BillApplicationService billService) {
        this(billService, null, null);
    }

    /**
     * 生产装配：显式标注 {@link Autowired}，因为本类有多个构造器重载
     * （与 OrderItemController 同款做法），否则 Spring 无法在多个候选里选择。
     */
    @Autowired
    public OrderBillController(BillApplicationService billService, OrderMapper orderMapper,
                               CustomerLookupMapper customerLookupMapper) {
        this.billService = billService;
        this.orderMapper = orderMapper;
        this.customerLookupMapper = customerLookupMapper;
    }

    @GetMapping("/bill")
    public BillResult bill(@PathVariable Long orderId) {
        requireBillAccess(orderId);
        return billService.buildBill(orderId);
    }

    /** 商户权限码优先；消费者会话只放行本人订单（见类注释）。 */
    private void requireBillAccess(Long orderId) {
        if (hasPermission(PERMISSION_VIEW_ORDER)) {
            return;
        }
        OrderPo order = orderMapper == null ? null : orderMapper.selectById(orderId);
        TenantContext context = TenantContextHolder.get();
        Long memberId = context == null || customerLookupMapper == null
                ? null
                : customerLookupMapper.findMemberId(context.tenantId(), context.accountId());
        if (memberId == null) {
            // 既没有商户权限也不是会员：与其它自助端点保持同一文案（不泄露订单是否存在）。
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + PERMISSION_VIEW_ORDER);
        }
        if (order == null || order.getCustomerId() == null || !memberId.equals(order.getCustomerId())) {
            throw new ApiException(403, "ORDER_SCOPE_DENIED", "只能查看和操作本人订单");
        }
    }

    /** 当前签名上下文是否持有权限码（消费端/B 端权限都来自签名 token，客户端无法伪造）。 */
    private static boolean hasPermission(String permissionCode) {
        TenantContext context = TenantContextHolder.get();
        return context != null && context.permissions() != null
                && context.permissions().contains(permissionCode);
    }
}
