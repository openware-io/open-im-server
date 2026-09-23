package io.openware.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 租户支付方式：平台授权（granted）+ 租户向用户开放开关（user_enabled，默认开）。现金兜底无需记录。 */
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
