package io.openware.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款审批与线下退款规则；storeId 为空表示租户/业态默认。 */
@Getter
@Setter
@TableName("pay_refund_rule_config")
public class PayRefundRuleConfigPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private String businessType;
    private Long storeId;
    private BigDecimal approvalThreshold;
    private Integer offlineRefundEnabled;
    private Integer version;
    private String idempotencyKey;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
