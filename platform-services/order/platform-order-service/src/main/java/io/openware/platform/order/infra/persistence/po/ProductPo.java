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

@Getter
@Setter
@TableName(value = "ord_product", autoResultMap = true)
public class ProductPo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long storeId;
    private String productCode;
    private String name;
    private String category;
    /** 商品类型：PRODUCT（实物商品，默认）/ SERVICE（服务：人员的服务）。 */
    private String itemType;
    private String unit;
    private BigDecimal salePrice;
    private Long materialId;
    /** 服务型商品关联的服务人员（res_resource.id，resource_type=KTV_SERVER）；实物商品恒为 NULL。 */
    private Long serverResourceId;
    private Boolean stockControlled;
    private Long catalogItemId;
    private String status;
    private Integer sortOrder;
    private String description;
    /** 图片 URL 列表（JSON 数组，最多 9 张）。 */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class) private List<String> imageUrls;
    /** 主图 URL，必须属于 imageUrls。 */
    private String mainImageUrl;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    /**
     * 非持久化：服务人员名称（列表/详情按 {@code server_resource_id} 批量回填）。
     * 资源服务不可达时降级为 null，绝不因为跨域读失败而阻断商品列表。
     */
    @TableField(exist = false)
    private String serverResourceName;
}
