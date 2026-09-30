package io.openware.platform.order.domain.overview;

import java.math.BigDecimal;

/** 按门店、业态和币种分组的订单经营事实。 */
public record OrderOverviewRow(Long storeId, String businessType, String currencyCode,
                               long orderCount, BigDecimal revenueAmount, BigDecimal paidAmount) {
    public OrderOverviewRow {
        revenueAmount = revenueAmount == null ? BigDecimal.ZERO : revenueAmount;
        paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
    }
}
