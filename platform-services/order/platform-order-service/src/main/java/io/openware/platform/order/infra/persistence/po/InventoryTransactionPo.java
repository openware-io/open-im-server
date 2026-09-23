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
@TableName("ord_inventory_transaction")
public class InventoryTransactionPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private Long materialId;
    private String transactionType;
    private BigDecimal quantityDelta;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    /**
     * 单价（最小货币单位/计量单位，V24）：入库 = 该批次单价，出库 = 当次结转的移动加权平均成本。
     * 升级前的历史流水为 NULL —— 当时没有采集批次单价，无法事后还原，不用 0 冒充真实成本。
     */
    private BigDecimal unitCost;
    /** 成本发生额（最小货币单位，V24，带符号：与 {@link #quantityDelta} 同号，入库正、出库负）。 */
    private BigDecimal totalCost;
    /** {@link #unitCost}/{@link #totalCost} 的币种快照（V24），缺省 USD。 */
    private String currencyCode;
    private String sourceType;
    private String sourceId;
    private String idempotencyKey;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
}
