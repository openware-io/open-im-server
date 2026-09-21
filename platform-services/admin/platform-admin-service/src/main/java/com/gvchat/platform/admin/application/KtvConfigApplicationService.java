package com.gvchat.platform.admin.application;

import com.gvchat.platform.admin.api.ktv.PaymentSwitchConfig;
import com.gvchat.platform.admin.api.ktv.PricingPlan;
import com.gvchat.platform.admin.api.ktv.ServerCatalogItem;
import com.gvchat.platform.admin.infra.KtvConfigDomainClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * KTV 配置 BFF 应用服务：计价方案 / 支付开关 / 服务人员目录，经 {@link KtvConfigDomainClient} 转发。
 *
 * <p><b>储值不在本服务（2026-09-19 合并）</b>：A380币储值（充值/退还/余额/流水）是<b>租户级</b>资产，
 * 与门店、KTV 计价配置无关，唯一入口是后台「储值管理」页 —— 它直连 customer 域
 * {@code /admin/wallets/recharge|refund} 与 {@code /business/members/{id}/wallet[/ledger]}。
 * 这里原先的 {@code /admin/ktv/wallet-recharge}（BFF 代理 + 用余额拼出一条假流水）已删除，
 * 避免同一功能两套入口、两处口径。
 */
@Service
public class KtvConfigApplicationService {

    private final KtvConfigDomainClient client;

    public KtvConfigApplicationService(KtvConfigDomainClient client) {
        this.client = client;
    }

    // —— 计价方案 ——
    public List<PricingPlan> pricingPlans(Long storeId) {
        return client.listPricingPlans(storeId);
    }

    public PricingPlan savePricingPlan(PricingPlan plan) {
        return client.upsertPricingPlan(plan);
    }

    // —— 支付开关 ——
    public List<PaymentSwitchConfig> paymentSwitches(Long storeId) {
        return client.listPaymentSwitches(storeId);
    }

    public PaymentSwitchConfig savePaymentSwitch(PaymentSwitchConfig config) {
        return client.upsertPaymentSwitch(config);
    }

    // —— 服务人员 ——
    public List<ServerCatalogItem> serverCatalog(Long storeId) {
        return client.listServerCatalog(storeId);
    }

    public ServerCatalogItem saveServer(ServerCatalogItem item) {
        return client.upsertServerCatalogItem(item);
    }
}
