package com.gvchat.common.payment.api.controller;

import com.gvchat.common.payment.application.PaymentMethodApplicationService;
import com.gvchat.common.payment.application.TenantPaymentMethodDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 支付方式可用性查询 + 平台授权/租户开关（统一口径）。 */
@RestController
public class PaymentMethodController {
    private final PaymentMethodApplicationService paymentMethodService;

    public PaymentMethodController(PaymentMethodApplicationService paymentMethodService) {
        this.paymentMethodService = paymentMethodService;
    }

    /** 统一可用性查询：B端收银 view=admin；C端支付 view=user。 */
    @GetMapping("/business/payment-methods")
    public List<PaymentMethodApplicationService.PaymentMethodView> methods(@RequestParam(defaultValue = "admin") String view) {
        return paymentMethodService.methods(view);
    }

    /** 平台：某租户支付方式授权/开关清单。 */
    @GetMapping("/admin/payment-methods/grants")
    public List<TenantPaymentMethodDto> listGrants(@RequestParam Long tenantId) {
        return paymentMethodService.listGrants(tenantId);
    }

    /** 平台：授权/回收。 */
    @PostMapping("/admin/payment-methods/grants")
    public TenantPaymentMethodDto setGrant(@RequestBody GrantRequest req) {
        return paymentMethodService.setGrant(req.tenantId(), req.method(), req.granted());
    }

    /** 租户：是否向用户开放（默认开）。 */
    @PostMapping("/admin/payment-methods/switch")
    public TenantPaymentMethodDto setUserEnabled(@RequestBody SwitchRequest req) {
        return paymentMethodService.setUserEnabled(req.tenantId(), req.method(), req.enabled());
    }

    public record GrantRequest(Long tenantId, String method, boolean granted) {}
    public record SwitchRequest(Long tenantId, String method, boolean enabled) {}
}
