package io.openware.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("pay_transaction")
public class PayTransactionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long paymentIntentId;
    private String provider;
    private String providerTransactionNo;
    private String providerPayloadDigest;
    private BigDecimal amount;
    private String currencyCode;
    private BigDecimal exchangeRate;
    private BigDecimal feeAmount;
    private String status;
    private LocalDateTime occurredAt;
    private String rawReference;
}
