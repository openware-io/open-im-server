package com.gvchat.common.payment.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 支付事件消费去重表（pay_event_consumed）。event_id 唯一键兜底消费幂等（SAAS_PLATFORM_06_TECHNICAL.md §8）。
 */
@Getter
@Setter
@TableName("pay_event_consumed")
public class PayEventConsumedPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private LocalDateTime consumedAt;
}
