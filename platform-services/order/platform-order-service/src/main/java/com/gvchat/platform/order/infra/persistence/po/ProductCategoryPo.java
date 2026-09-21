package com.gvchat.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 商品分类（商品管理页的分类字典，按门店维护）。 */
@Getter
@Setter
@TableName("ord_product_category")
public class ProductCategoryPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String name;
    /** 排序，越小越靠前。 */
    private Integer sortOrder;
    /** ACTIVE/DISABLED。 */
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
