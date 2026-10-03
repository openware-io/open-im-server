package io.openware.platform.admin.application;

import io.openware.platform.admin.api.ktv.PaymentSwitchConfig;
import io.openware.platform.admin.api.ktv.PricingPlan;
import io.openware.platform.admin.api.ktv.ServerCatalogItem;
import io.openware.platform.admin.api.ktv.PointRuleConfig;
import io.openware.platform.admin.api.ktv.ReservationRuleConfig;
import io.openware.platform.admin.api.ktv.PaymentRuleConfig;
import io.openware.platform.admin.api.ktv.VoidRuleConfig;
import io.openware.platform.admin.infra.KtvConfigDomainClient;
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
        return client.listPricingPlans(storeId, null);
    }

    public List<PricingPlan> pricingPlans(Long storeId, String businessType) {
        return client.listPricingPlans(storeId, businessType);
    }

    public PricingPlan savePricingPlan(PricingPlan plan) {
        return client.upsertPricingPlan(plan);
    }

    public PricingPlan savePricingPlanBatch(PricingPlan.BatchCommand command) {
        return client.upsertPricingPlanBatch(command);
    }

    // —— 支付开关 ——
    public List<PaymentSwitchConfig> paymentSwitches(Long storeId) {
        return client.listPaymentSwitches(storeId, null);
    }

    public List<PaymentSwitchConfig> paymentSwitches(Long storeId, String businessType) {
        return client.listPaymentSwitches(storeId, businessType);
    }

    public PaymentSwitchConfig savePaymentSwitch(PaymentSwitchConfig config) {
        return client.upsertPaymentSwitch(config);
    }

    public PaymentSwitchConfig savePaymentSwitchBatch(PaymentSwitchConfig.BatchCommand command) {
        return client.upsertPaymentSwitchBatch(command);
    }

    // —— 服务人员 ——
    public List<ServerCatalogItem> serverCatalog(Long storeId) {
        return client.listServerCatalog(storeId);
    }

    public ServerCatalogItem saveServer(ServerCatalogItem item) {
        return client.upsertServerCatalogItem(item);
    }

    public PointRuleConfig pointRule(Long storeId, String businessType) { return client.pointRule(storeId, businessType); }
    public PointRuleConfig savePointRule(PointRuleConfig config) { return client.savePointRule(config); }
    public ReservationRuleConfig reservationRule(Long storeId, String businessType) { return client.reservationRule(storeId, businessType); }
    public ReservationRuleConfig saveReservationRule(ReservationRuleConfig config) { return client.saveReservationRule(config); }
    public PaymentRuleConfig paymentRule(Long storeId, String businessType) { return client.paymentRule(storeId, businessType); }
    public PaymentRuleConfig savePaymentRule(PaymentRuleConfig config) { return client.savePaymentRule(config); }
    public VoidRuleConfig voidRule(Long storeId, String businessType) { return client.voidRule(storeId, businessType); }
    public VoidRuleConfig saveVoidRule(VoidRuleConfig config) { return client.saveVoidRule(config); }
}
