package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@TableName(value = "ord_inventory_material", autoResultMap = true)
public class InventoryMaterialPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private String materialCode;
    private String name;
    private String category;
    private String unit;
    /** 描述（≤255，业务侧 ItemDescriptions 校验）。 */
    private String description;
    private BigDecimal safetyStock;
    /**
     * 采购价（最小货币单位：分，每计量单位）；未维护为 null，与 0 分区分开。
     *
     * <p>{@code updateStrategy = ALWAYS}：MyBatis-Plus 默认 NOT_NULL 会把 null 字段从 UPDATE
     * 语句里剔除，导致「0 = 清空采购价」写不进 NULL（清空静默失效）。本列只由
     * {@code InventoryApplicationService.updateMaterial} 写入，且写前必读整行，因此放开为
     * 每次都写是安全的；其它列仍保持默认的部分更新语义。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private BigDecimal purchasePrice;
    /** 采购价币种（purchase_price 的计价币种快照；入库成本/成本毛利报表按它归集，缺省 USD）。 */
    private String currencyCode;
    /** 图片 URL 列表（JSON 数组，最多 9 张）。 */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class) private List<String> imageUrls;
    /** 主图 URL，必须属于 imageUrls。 */
    private String mainImageUrl;
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    @TableField(exist = false) private BigDecimal onHandQty;
    @TableField(exist = false) private BigDecimal reservedQty;
}
