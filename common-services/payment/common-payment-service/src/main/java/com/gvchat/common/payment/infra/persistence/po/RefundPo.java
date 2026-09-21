package com.gvchat.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("pay_refund")
public class RefundPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long orderId;
    private Long paymentTransactionId;
    private String requestId;
    private BigDecimal requestedAmount;
    private BigDecimal approvedAmount;
    /** 币种快照（退款退原币种：原订单/原支付流水币种；跨币种退款禁止）。 */
    private String currencyCode;
    private String providerRefundNo;
    private String status;
    private String reason;
    private Long requestedBy;
    private Long approvedBy;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
