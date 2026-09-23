package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 订单事件消费去重表（ord_event_consumed）。event_id 唯一键兜底消费幂等（SAAS_PLATFORM_06_TECHNICAL.md §8）。
 */
@Getter
@Setter
@TableName("ord_event_consumed")
public class OrdEventConsumedPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private LocalDateTime consumedAt;
}
