package io.openware.platform.admin.api.controller;

import io.openware.platform.admin.api.ktv.PaymentSwitchConfig;
import io.openware.platform.admin.api.ktv.PricingPlan;
import io.openware.platform.admin.api.ktv.ServerCatalogItem;
import io.openware.platform.admin.application.KtvConfigApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KTV 配置 BFF（租户后台 / 门店后台）：计价方案 / 服务人员 / 支付开关（KTV_BUSINESS_03_ADMIN）。
 * 骨架：内部经 KtvConfigDomainClient 占位调用 pricing/payment/customer(resource) 领域服务，
 * 当前返回可配置默认数据；所有写命令需携带 Idempotency-Key。
 *
 * <p><b>储值不在这里（2026-09-19）</b>：原先本控制器还暴露 {@code /admin/ktv/wallet-recharge}
 * （充值/退还 + 用余额拼出来的假流水），与租户后台「储值管理」页重复且要求先选门店。
 * 储值是租户级、跨门店共用的资产，唯一入口为「储值管理」页（直连 customer 域
 * {@code /admin/wallets/*} 与 {@code /business/members/{id}/wallet[/ledger]}），
 * 该 BFF 链路已删除。
 */
@RestController
@RequestMapping("/admin/ktv")
public class KtvConfigController {

    private final KtvConfigApplicationService service;

    public KtvConfigController(KtvConfigApplicationService service) {
        this.service = service;
    }

    // —— 计价方案 ——
    @GetMapping("/pricing-plans")
    public List<PricingPlan> pricingPlans(@RequestParam(required = false) Long storeId) {
        return service.pricingPlans(storeId);
    }

    @PostMapping("/pricing-plans")
    public PricingPlan createPricingPlan(@RequestBody PricingPlan plan) {
        return service.savePricingPlan(plan);
    }

    @PutMapping("/pricing-plans/{id}")
    public PricingPlan updatePricingPlan(@PathVariable Long id, @RequestBody PricingPlan plan) {
        return service.savePricingPlan(plan);
    }

    // —— 支付开关 ——
    @GetMapping("/payment-switches")
    public List<PaymentSwitchConfig> paymentSwitches(@RequestParam(required = false) Long storeId) {
        return service.paymentSwitches(storeId);
    }

    @PostMapping("/payment-switches")
    public PaymentSwitchConfig createPaymentSwitch(@RequestBody PaymentSwitchConfig config) {
        return service.savePaymentSwitch(config);
    }

    @PutMapping("/payment-switches/{id}")
    public PaymentSwitchConfig updatePaymentSwitch(@PathVariable Long id, @RequestBody PaymentSwitchConfig config) {
        return service.savePaymentSwitch(config);
    }

    // —— 服务人员 ——
    @GetMapping("/server-catalog")
    public List<ServerCatalogItem> serverCatalog(@RequestParam(required = false) Long storeId) {
        return service.serverCatalog(storeId);
    }

    @PostMapping("/server-catalog")
    public ServerCatalogItem createServer(@RequestBody ServerCatalogItem item) {
        return service.saveServer(item);
    }

    @PutMapping("/server-catalog/{id}")
    public ServerCatalogItem updateServer(@PathVariable Long id, @RequestBody ServerCatalogItem item) {
        return service.saveServer(item);
    }
}
