package io.openware.common.payment.infra.persistence.po;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠道结算用的订单口径投影（只读，不落表）。
 *
 * <p>只承载结算需要判定的列：租户/门店 scope、交易与退款状态、金额与币种快照。
 * 支付域不拥有 {@code ord_order} 的其余业务列，不应把整行读进本域。
 */
@Getter
@Setter
public class ChannelOrderSnapshotPo {
    private Long orderId;
    private Long tenantId;
    private Long storeId;
    private String tradeStatus;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal refundableAmount;
    private String refundStatus;
    private String currencyCode;
}
