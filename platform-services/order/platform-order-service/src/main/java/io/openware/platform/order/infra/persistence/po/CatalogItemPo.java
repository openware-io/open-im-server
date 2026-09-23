package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 商品/服务目录项（B 端点单、C 端自助加项的统一目录）。 */
@Getter
@Setter
@TableName(value = "ord_catalog_item", autoResultMap = true)
public class CatalogItemPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Long tenantId;
    private Long storeId;
    private String category;
    private String itemType;
    private String name;
    private String unit;
    private BigDecimal unitPrice;
    private String description;
    /** 图片 URL 列表（JSON 数组，最多 9 张）；镜像自关联商品，商品无图时取关联物料。 */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)
    private List<String> imageUrls;
    /** 主图 URL，必须属于 imageUrls。 */
    private String mainImageUrl;
    private String status;
    private Boolean stockControlled;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    /** 非持久化：当前是否可点（未上架/未关联物料/售罄时为 false，前端置灰并展示原因）。 */
    @TableField(exist = false)
    private Boolean available;
    /** 非持久化：不可点原因（售罄/未上架/未关联物料）。 */
    @TableField(exist = false)
    private String unavailableReason;
    /**
     * 非持久化：受库存控制时的**可用库存数量**（不控制库存为 null = 不限量）。
     * 点单/加项页据此限制「加号」次数，在提交前就拦住超卖；最终扣减仍由 add-item 的原子扣减兜底。
     */
    @TableField(exist = false)
    private BigDecimal availableQuantity;
}
