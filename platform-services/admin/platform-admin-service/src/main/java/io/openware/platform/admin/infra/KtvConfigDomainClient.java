package io.openware.platform.admin.infra;

import io.openware.platform.admin.api.ktv.PaymentSwitchConfig;
import io.openware.platform.admin.api.ktv.PricingPlan;
import io.openware.platform.admin.api.ktv.ServerCatalogItem;

import java.util.List;

/**
 * KTV 配置领域服务客户端（BFF -> 领域服务）。
 * 真实实现 {@link RestKtvConfigDomainClient}：
 *  - tenant 服务：计价方案（PricingPlan）内部端点 /internal/pricing-plans；
 *  - payment 服务：门店支付开关（tnt_store_payment_config，默认关闭；领域端点待补，当前占位）；
 *  - resource 服务：服务人员目录（res_resource KTV_SERVER）内部端点 /internal/resources。
 * 储值（cst_wallet_*）不在此接口：它是租户级资产，唯一入口是「储值管理」页直连 customer 域。
 */
public interface KtvConfigDomainClient {

    List<PricingPlan> listPricingPlans(Long storeId);

    PricingPlan upsertPricingPlan(PricingPlan plan);

    List<PaymentSwitchConfig> listPaymentSwitches(Long storeId);

    PaymentSwitchConfig upsertPaymentSwitch(PaymentSwitchConfig config);

    List<ServerCatalogItem> listServerCatalog(Long storeId);

    ServerCatalogItem upsertServerCatalogItem(ServerCatalogItem item);
}
