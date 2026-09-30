package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("ord_order_void_approval")
public class OrdOrderVoidApprovalPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private String businessType;
    private Long orderId;
    private Integer orderVersion;
    private String orderStatusSnapshot;
    private String orderSnapshotJson;
    private String reason;
    private String status;
    private Long applicantId;
    private Long approverId;
    private String reviewComment;
    private String idempotencyKey;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime reviewedAt;
}
