package io.openware.platform.order.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 仓库 / 库位持久化对象（{@code ord_inventory_warehouse}，V25）。
 *
 * <p>列集合：{@code id / tenant_id / organization_id / business_type / store_id / code / name /
 * status / version / created_by / created_at / updated_by / updated_at}；唯一键
 * {@code (tenant_id, business_type, store_id, code)}。
 */
@Getter
@Setter
@TableName("ord_inventory_warehouse")
public class InventoryWarehousePo {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    /** 组织快照（归属 / 报表，不进入唯一键）。 */
    private Long organizationId;
    /** 业态快照（作用域）。 */
    private String businessType;
    private Long storeId;
    /** 门店内仓库编码（默认仓为 DEFAULT，创建后不可改）。 */
    private String code;
    private String name;
    /** ACTIVE / DISABLED。 */
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
