package com.gvchat.platform.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 租户支付方式授权/开关（与 common-payment-service 同表 tenant_payment_method，后台菜单权限过滤只读）。 */
@Getter
@Setter
@TableName("tenant_payment_method")
public class TenantPaymentMethodPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String method;
    private Integer granted;
    private Integer userEnabled;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
