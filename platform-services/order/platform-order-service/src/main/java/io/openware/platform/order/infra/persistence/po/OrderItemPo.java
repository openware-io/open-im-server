package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_order_item")
public class OrderItemPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long orderId;
    private String itemType;
    private Long catalogItemId;
    private Long resourceId;
    private String nameSnapshot;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    /** 币种快照（明细落库时的租户币种；已结算明细锁定，改设置不改历史）。 */
    private String currencyCode;
    private String priceSnapshotJson;
    private String status;
    /** 加项来源 MERCHANT(商户/服务员，直接生效) / CUSTOMER(消费者，待确认)。 */
    private String source;
    private String inventoryStatus;
    private Long inventoryMaterialId;
    private String inventoryRecoveryDecision;
    private String inventoryRecoveryReason;
    private Long inventoryRecoveryDecidedBy;
    private LocalDateTime inventoryRecoveryDecidedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
