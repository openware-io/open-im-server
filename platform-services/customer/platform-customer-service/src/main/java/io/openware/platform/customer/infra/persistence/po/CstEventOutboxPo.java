package io.openware.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Customer 事实事件 Outbox（与业务记账同库、同事务）。 */
@Getter
@Setter
@TableName("cst_event_outbox")
public class CstEventOutboxPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
