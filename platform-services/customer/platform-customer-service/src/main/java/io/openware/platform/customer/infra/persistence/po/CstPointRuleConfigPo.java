package io.openware.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Customer 域积分经营规则；storeId=0 表示租户/业态默认。 */
@Getter
@Setter
@TableName("cst_point_rule_config")
public class CstPointRuleConfigPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String businessType;
    private Long storeId;
    private BigDecimal earnRate;
    private BigDecimal redeemRate;
    private Integer expiryDays;
    private Long redeemCapPoints;
    private Integer version;
    private String idempotencyKey;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
