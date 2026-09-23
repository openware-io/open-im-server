package io.openware.common.payment.infra.persistence.po;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OrderBillingPo {
    private Long id;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    /** 订单币种快照（ord_order.currency_code）：收款/退款必须与它一致（禁止跨币种）。 */
    private String currencyCode;
}
