package io.openware.common.payment.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.DailyClosingDto;
import io.openware.common.payment.application.OrderCollectionsDto;
import io.openware.common.payment.application.PayIntentDto;
import io.openware.common.payment.application.PaymentQueryApplicationService;
import io.openware.common.payment.application.RefundDto;
import io.openware.common.payment.application.ShiftDto;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收银/支付/交班/日结/退款 查询端点（只读列表）。
 * 租户边界由 TenantLineInnerInterceptor 按 X-Tenant-Context 自动过滤（tenant_id）。
 * 网关 /api/v1/business/payments|shifts|refund-requests/** 与 /api/v1/admin/daily-closings/** 路由到本服务。
 *
 * <p><b>统一时间区间</b>：四个列表端点都接受 {@code from}/{@code to}，语义为**闭区间**，
 * 由 {@link TimeRangeParams#parse} 统一解析（日期形态的 {@code from} → 当天 {@code 00:00:00.000}、
 * {@code to} → 当天 {@code 23:59:59.999}；也接受 {@code yyyy-MM-ddTHH:mm:ss}）；为空 = 不筛；
 * {@code from > to} 或格式非法 → 400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码）。
 * 业务时间列：支付/退款为 {@code created_at}，交班为 {@code opened_at}，日结为营业日 {@code business_date}。
 *
 * <p>另有按订单的收款明细读端点 {@code GET /business/payments/order-collections}（批量，不看时间区间），
 * 供订单管理核对组合支付的分腿、渠道流水与退款。
 */
@RestController
@RequestMapping("")
public class PaymentQueryController {
    private final PaymentQueryApplicationService service;

    public PaymentQueryController(PaymentQueryApplicationService service) { this.service = service; }

    /** 交班列表（当前租户，按交班时间 {@code opened_at} 闭区间筛选，创建时间倒序）。 */
    @GetMapping("/business/shifts")
    public List<ShiftDto> shifts(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to) {
        return service.shifts(TimeRangeParams.parse(from, to));
    }

    /** 支付流水列表（pay_intent 口径，按支付发生时刻 {@code created_at} 闭区间筛选，倒序）。 */
    @GetMapping("/business/payments")
    public List<PayIntentDto> payments(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        return service.payments(TimeRangeParams.parse(from, to));
    }

    /**
     * 订单收款明细（批量）：订单管理 / 收银台用它把「组合支付到底怎么收的」一次看全 ——
     * 每笔已确认收款的分腿（积分/储值/现金/支付宝/微信/Stripe 各自一行，不合并）、
     * 渠道支付流水（含渠道交易号与状态）与退款记录。**没有收款的订单不出现在结果里**。
     *
     * <p>权限 {@code order.view}：这是按订单查资金的读接口，必须挡住 C 端消费者令牌
     * （消费者没有 {@code order.view}），否则可凭 orderId 猜读他人收款明细。
     * 租户隔离在应用层显式带上 {@code tenant_id}。
     *
     * <p>{@code orderIds} 必须非空且不超过 {@link PaymentQueryApplicationService#MAX_ORDER_IDS} 个，
     * 超限 400（避免一次把全租户收款拉出来）。Spring 支持 {@code ?orderIds=1,2,3} 逗号写法。
     */
    @GetMapping("/business/payments/order-collections")
    public List<OrderCollectionsDto> orderCollections(@RequestParam(required = false) List<Long> orderIds) {
        PermissionGuard.require("order.view");
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null || ctx.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        if (orderIds == null || orderIds.isEmpty()) {
            throw new ApiException(400, "ORDER_IDS_REQUIRED", "orderIds 不能为空");
        }
        if (orderIds.size() > PaymentQueryApplicationService.MAX_ORDER_IDS) {
            throw new ApiException(400, "ORDER_IDS_TOO_MANY",
                    "orderIds 一次最多 " + PaymentQueryApplicationService.MAX_ORDER_IDS + " 个");
        }
        return service.orderCollections(ctx.tenantId(), orderIds);
    }

    /** 日结列表（当前租户，按营业日 {@code business_date} 闭区间筛选，创建时间倒序）。 */
    @GetMapping("/admin/daily-closings")
    public List<DailyClosingDto> dailyClosings(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        return service.dailyClosings(TimeRangeParams.parse(from, to));
    }

    /** 退款申请列表（当前租户，按申请发生时刻 {@code created_at} 闭区间筛选，倒序）。 */
    @GetMapping("/business/refund-requests")
    public List<RefundDto> refundRequests(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return service.refundRequests(TimeRangeParams.parse(from, to));
    }
}
