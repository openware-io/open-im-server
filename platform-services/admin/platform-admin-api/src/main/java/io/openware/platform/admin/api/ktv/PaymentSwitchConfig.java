package io.openware.platform.admin.api.ktv;

import java.util.List;

/**
 * 门店支付开关配置（KTV_BUSINESS_03_ADMIN §5）。
 * 对应 tnt_store_payment_config；线上渠道（支付宝/微信/Stripe）默认关闭，
 * 现金/A380币/积分不依赖渠道配置。
 */
public record PaymentSwitchConfig(
        Long id,
        Long storeId,
        String storeName,
        Long merchantAccountId,
        String currencyCode,               // 缺省取租户币种（USD）
        List<PaymentChannelSwitch> channels
) {

    public record PaymentChannelSwitch(
            String channel,                // ALIPAY / WECHAT / STRIPE
            String name,                   // 支付宝 / 微信支付 / Stripe
            Boolean enabled,               // 默认 false
            Boolean refundEnabled,         // 默认 false
            Long minAmount,                // 最小单笔（最小货币单位整数）
            Long maxAmount                 // 最大单笔（最小货币单位整数）
    ) {}
}
