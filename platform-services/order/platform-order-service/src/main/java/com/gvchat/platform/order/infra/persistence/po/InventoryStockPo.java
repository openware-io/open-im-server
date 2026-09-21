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
@TableName("ord_inventory_stock")
public class InventoryStockPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private Long materialId;
    private BigDecimal onHandQty;
    private BigDecimal reservedQty;
    /**
     * 移动加权平均成本（最小货币单位/计量单位，V24）。
     *
     * <p>只在**入库**时重算，出库只按它结转发生额；0 表示「尚未建立成本基准」
     * （历史无采购价的物料、或从未入库过），与物料 {@code purchase_price} 的「未维护 = NULL」语义对齐。
     */
    private BigDecimal avgCost;
    /** {@link #avgCost} 的币种快照（V24），缺省 USD；币种变更绝不与历史成本混合累加。 */
    private String currencyCode;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
