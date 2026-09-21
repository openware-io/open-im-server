package com.gvchat.common.payment.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.payment.application.CollectApplicationService;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组合收款（对齐 SAAS_PLATFORM_05 §7 / KTV_BUSINESS_01 §7.2）：
 * 入参 {customerId, payments:[{method, amount}], expectedVersion}，金额最小货币单位整数；应收由服务端账单决定。
 * 服务端按 积分→储值→现金 逐笔拆分，每笔 ≤ 剩余应收，返回剩余与各渠道已收。
 * 租户来自 X-Tenant-Context 上下文（禁止信任请求体）；网关统一加 /api/v1 前缀，本服务只暴露 /business/**。
 */
@RestController
@RequestMapping("/business/orders/{orderId}")
public class CollectController {
    private final CollectApplicationService collectService;

    public CollectController(CollectApplicationService collectService) { this.collectService = collectService; }

    /**
     * 组合收款。Idempotency-Key 声明为 {@code required = false}，缺失/为空时由应用层统一抛
     * 400 IDEMPOTENCY_KEY_REQUIRED（中文统一体），不让 Spring 的绑定异常漏出默认错误体。
     */
    @PostMapping("/collect")
    public CollectApplicationService.CollectResult collect(@PathVariable Long orderId,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                           @RequestBody CollectRequest req) {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        Long customerId = req.customerId();
        if (ctx.permissions() != null && ctx.permissions().contains("payment.collect")) {
            PermissionGuard.require("payment.collect");
        } else {
            customerId = collectService.consumerCustomerId(ctx.tenantId(), orderId, ctx.accountId());
            if (customerId == null) {
                throw new ApiException(403, "ORDER_OWNER_REQUIRED", "只能支付本人订单");
            }
        }
        return collectService.collect(ctx.tenantId(), ctx.storeId(), orderId, customerId, req.currencyCode(),
                req.payable(),
                req.payments() == null ? List.of()
                        : req.payments().stream().filter(p -> p != null)
                                .map(p -> new CollectApplicationService.PaymentItem(p.method(), p.amount()))
                                .toList(),
                idempotencyKey);
    }

    public record CollectRequest(Long customerId, String currencyCode, long payable, List<Payment> payments) {}
    public record Payment(String method, long amount) {}
}
