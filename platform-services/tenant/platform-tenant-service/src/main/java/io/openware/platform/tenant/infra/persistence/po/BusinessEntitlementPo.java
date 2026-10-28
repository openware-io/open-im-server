package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tnt_business_entitlement")
public class BusinessEntitlementPo {
    @TableId
    private Long id;
    private Long tenantId;
    private String businessType;
    private String status;
    private String source;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
