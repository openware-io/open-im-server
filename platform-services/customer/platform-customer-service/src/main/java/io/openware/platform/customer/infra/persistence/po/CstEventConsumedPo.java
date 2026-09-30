package io.openware.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cst_event_consumed")
public class CstEventConsumedPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private LocalDateTime consumedAt;
}
