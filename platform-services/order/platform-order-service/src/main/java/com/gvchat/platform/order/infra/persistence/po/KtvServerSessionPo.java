package com.gvchat.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_ktv_server_session")
public class KtvServerSessionPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long orderId;
    private Long ktvSessionId;
    private Long serverResourceId;
    private Long catalogItemId;
    private LocalDateTime orderedAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String billingUnit;
    private Integer incrementMinutes;
    private String roundingDirection;
    private Long pricePerInc;
    private Integer durationSeconds;
    private Integer durationMinutes;
    private BigDecimal totalAmount;
    /** 币种快照（服务人员费 totalAmount 落库时的租户币种）。 */
    private String currencyCode;
    private String priceSnapshotJson;
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
