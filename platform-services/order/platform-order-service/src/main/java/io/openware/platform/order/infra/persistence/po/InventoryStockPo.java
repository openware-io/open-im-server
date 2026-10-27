package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存余额持久化对象（{@code ord_inventory_stock}）。
 *
 * <p>列集合来自真实 DDL（V1 基线 + V2 revision + V24 organization/business + V25 warehouse）：
 * {@code id / tenant_id / organization_id / business_type / store_id / warehouse_id / material_id /
 * on_hand_qty / reserved_qty / avg_cost / currency_code / revision / created_by / created_at /
 * updated_by / updated_at}。
 *
 * <p>作用域唯一键：{@code (tenant_id, business_type, store_id, warehouse_id, material_id)}。
 * {@code organization_id} 是归属 / 报表快照，**不在唯一键里**。
 */
@Getter
@Setter
@TableName("ord_inventory_stock")
public class InventoryStockPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    /** 组织快照（归属 / 报表，不进入唯一键）。 */
    private Long organizationId;
    /** 业态快照（作用域；V24 回填，写入时取签名门店上下文）。 */
    private String businessType;
    private Long storeId;
    /** 仓 / 库位（V25；作用域键的一部分）。 */
    private Long warehouseId;
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
    /** 库存修订计数器，不是 CAS 条件；并发保护由数量条件 UPDATE 承担。 */
    private Integer revision;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
