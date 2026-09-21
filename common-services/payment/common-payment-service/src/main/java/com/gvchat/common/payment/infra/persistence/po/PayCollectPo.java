package com.gvchat.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("pay_collect")
public class PayCollectPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String collectNo;
    private Long orderId;
    private String idempotencyKey;
    private String state;
    /** 币种快照（本次组合收款各分腿与找零的币种；收款币种必须等于订单币种）。 */
    private String currencyCode;
    private String requestJson;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
