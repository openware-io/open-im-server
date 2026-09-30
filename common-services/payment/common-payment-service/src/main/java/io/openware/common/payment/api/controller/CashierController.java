package io.openware.common.payment.api.controller;

import io.openware.common.payment.application.CashierApplicationService;
import io.openware.common.payment.application.DailyClosingDto;
import io.openware.common.payment.application.RefundApplicationService;
import io.openware.common.payment.application.RefundDto;
import io.openware.common.payment.application.ShiftDto;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.common.exception.ApiException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("")
public class CashierController {
    private final CashierApplicationService cashierService;
    private final RefundApplicationService refundService;

    public CashierController(CashierApplicationService cashierService, RefundApplicationService refundService) {
        this.cashierService = cashierService;
        this.refundService = refundService;
    }

    /**
     * 开班（登记开班现金备付金）。
     *
     * <p><b>金额单位口径（冻结）</b>：{@code openingCash} 一律为<b>最小货币单位</b>
     * （CNY 分 / USD cent）的<b>非负整数</b>，本接口只做转发，<b>不做任何分/元换算</b>。
     * null 视为 0；负数或带小数（非法分）由 {@link CashierApplicationService#openShift}
     * 返回 {@code 400 SHIFT_CASH_INVALID}。
     */
    @PostMapping("/business/shifts/open")
    public ShiftDto openShift(@RequestBody OpenShiftRequest req) {
        return cashierService.openShift(req.tenantId(), req.storeId(), req.terminalId(), req.operatorId(), req.openingCash());
    }

    /**
     * 交班（提交实盘现金，服务端算长短款）。
     *
     * <p><b>金额单位口径（冻结）</b>：{@code actualCash} 一律为<b>最小货币单位</b>
     * （CNY 分 / USD cent）的<b>非负整数</b>，与 {@code pay_intent.amount}、{@code pay_shift.opening_cash}
     * 同一量级；本接口只做转发，<b>不做任何分/元换算</b>。null 视为 0；负数或带小数（非法分）
     * 由 {@link CashierApplicationService#closeShift} 返回 {@code 400 SHIFT_CASH_INVALID}。
     */
    @PostMapping("/business/shifts/{id}/close")
    public ShiftDto closeShift(@PathVariable Long id, @RequestBody CloseShiftRequest req) {
        return cashierService.closeShift(id, req.actualCash(), req.remark());
    }

    @PostMapping("/admin/daily-closings/{id}/submit")
    public DailyClosingDto submit(@PathVariable Long id, @RequestBody DailyClosingRequest req) {
        return cashierService.submitDailyClosing(req.tenantId(), req.storeId(), req.businessDate(), req.submittedBy());
    }

    @PostMapping("/business/refund-requests")
    public RefundDto requestRefund(@RequestBody RefundRequest req) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        if (context.storeId() == null) {
            throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "退款申请必须在门店上下文中发起");
        }
        if (req.storeId() != null && !context.storeId().equals(req.storeId())) {
            throw new ApiException(422, "STORE_CONTEXT_MISMATCH", "请求门店与当前门店上下文不一致");
        }
        return refundService.requestRefund(context.tenantId(), context.storeId(), req.orderId(), req.amount(),
                req.reason(), req.requestedBy());
    }

    @PostMapping("/admin/refund-requests/{id}/approve")
    public RefundDto approveRefund(@PathVariable Long id, @RequestBody ApproveRefundRequest req) {
        PermissionGuard.require("payment.refund.approve");
        return refundService.approveRefund(id, req.approvedAmount(), req.approvedBy());
    }

    @PostMapping("/admin/refund-requests/{id}/reject")
    public RefundDto rejectRefund(@PathVariable Long id, @RequestBody RejectRefundRequest req) {
        PermissionGuard.require("payment.refund.approve");
        return refundService.rejectRefund(id, req.rejectedBy());
    }

    @PostMapping("/admin/refund-requests/{id}/refund")
    public RefundDto markRefunded(@PathVariable Long id, @RequestBody MarkRefundedRequest req) {
        PermissionGuard.require("payment.refund.offline");
        return refundService.markRefunded(id, req.providerRefundNo(), req.operatorId());
    }

    /**
     * 开班请求。
     *
     * @param openingCash 开班现金备付金：<b>最小货币单位</b>（CNY 分 / USD cent）非负整数，
     *                    禁止按「元/¥」提交或做分/元换算；null 视为 0
     */
    public record OpenShiftRequest(Long tenantId, Long storeId, Long terminalId, Long operatorId, BigDecimal openingCash) {}

    /**
     * 交班请求。
     *
     * @param actualCash 实盘现金：<b>最小货币单位</b>（CNY 分 / USD cent）非负整数，
     *                   与开班现金、{@code pay_intent.amount} 同量级；null 视为 0
     */
    public record CloseShiftRequest(BigDecimal actualCash, String remark) {}
    public record DailyClosingRequest(Long tenantId, Long storeId, LocalDate businessDate, Long submittedBy) {}
    public record RefundRequest(Long tenantId, Long storeId, Long orderId, BigDecimal amount, String reason, Long requestedBy) {}
    public record ApproveRefundRequest(BigDecimal approvedAmount, Long approvedBy) {}
    public record RejectRefundRequest(Long rejectedBy) {}
    public record MarkRefundedRequest(String providerRefundNo, Long operatorId) {}
}
