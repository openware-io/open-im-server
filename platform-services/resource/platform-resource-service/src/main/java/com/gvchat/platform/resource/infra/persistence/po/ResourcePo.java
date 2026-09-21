package com.gvchat.platform.resource.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@TableName(value = "res_resource", autoResultMap = true)
public class ResourcePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String resourceType;
    private String resourceCode;
    private String name;
    /** 所属区域（自由文本，如「三楼 A 区」）；NULL 表示未设置。 */
    private String areaName;
    private Long parentId;
    private Integer capacity;
    /** 房型ID（res_room_type.id，同门店）；NULL 表示未设置房型，计费走门店级单价。 */
    private Long roomTypeId;
    private String status;
    /** 清洁状态：IDLE 空闲可用 / CLEANING 清洁中（清洁中同样不可开台、不可预约）。 */
    private String cleaningStatus;
    private LocalDateTime cleaningStartedAt;
    /** 图片 URL 列表（JSON 数组，最多 9 张）；历史数据为 null，前端走占位图。 */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)
    private List<String> imageUrls;
    /** 主图 URL，必须属于 imageUrls。 */
    private String mainImageUrl;
    /** 描述，≤255。 */
    private String description;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    /**
     * 非持久化：房型编码/名称/房型单价（读路径按 room_type_id 关联 res_room_type 回填）。
     * 列表与内部快照都要展示「房型」并据此计费，避免每个调用方各自 join。
     */
    @TableField(exist = false)
    private String roomTypeCode;
    @TableField(exist = false)
    private String roomTypeName;
    /** 非持久化：房型房费单价（最小货币单位/计费单位）；null 或 &lt;= 0 表示回退门店级单价。 */
    @TableField(exist = false)
    private Long roomTypeUnitPrice;
    /** 非持久化：房型服务人员单价（最小货币单位/计费单位）；null 或 &lt;= 0 表示回退门店级单价。 */
    @TableField(exist = false)
    private Long roomTypeServerUnitPrice;
}
