package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Order 域预约经营规则。store_id=0 表示租户/业态默认，门店覆盖必须带业态。 */
@Getter
@Setter
@TableName("ord_reservation_rule_config")
public class OrdReservationRuleConfigPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String businessType;
    private Long storeId;
    private Integer advanceMinutes;
    private Integer cancelMinutes;
    private Integer rescheduleMinutes;
    private Integer version;
    private String idempotencyKey;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
