package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Order 域作废审批规则：租户/业态默认或门店覆盖。 */
@Getter
@Setter
@TableName("ord_void_rule_config")
public class OrdVoidRuleConfigPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String businessType;
    private Long storeId;
    private Boolean requireApproval;
    private Integer version;
    private String idempotencyKey;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
