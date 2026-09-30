package io.openware.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 门店本地日结切点配置，分钟范围 0-1439。 */
@Getter
@Setter
@TableName("pay_daily_closing_rule_config")
public class PayDailyClosingRuleConfigPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private Integer closingMinute;
    private Integer version;
    private String idempotencyKey;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
