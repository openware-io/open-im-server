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

/**
 * 包厢房型字典（门店级，见 V6__res_room_type_and_area.sql）。
 *
 * <p>房型是「包厢分类 + 房型单价」的权威数据：包厢通过 {@code res_resource.room_type_id} 引用它，
 * 订单域开台/结台时按包厢房型取单价（缺该房型则回退门店级单价）。
 *
 * <p>房型同时是 C 端「选择包厢类型」的展示单元（见 V8__res_room_type_media.sql）：图片与主图落在
 * 房型本身，C 端卡片优先用它，缺图才回落到包厢样板图 / 门店占位图。
 */
@Getter
@Setter
@TableName(value = "res_room_type", autoResultMap = true)
public class RoomTypePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    /** 门店内唯一的房型编码，如 SMALL/MEDIUM/VIP。 */
    private String code;
    /** 门店内唯一的房型名称，如「小包」。 */
    private String name;
    /** 标准容纳人数（开台人数上限的兜底值）。 */
    private Integer capacity;
    /** 图片 URL 列表（JSON 数组，最多 9 张）；历史数据为 null，前端走包厢样板图/占位图降级。 */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)
    private List<String> imageUrls;
    /** 主图 URL，必须属于 imageUrls。 */
    private String mainImageUrl;
    /** 房型单价（房费，最小货币单位/计费单位）；null 或 <= 0 表示回退门店级单价。 */
    private Long unitPrice;
    /** 房型服务人员单价（最小货币单位/计费单位）；null 或 <= 0 表示回退门店级服务人员单价。 */
    private Long serverUnitPrice;
    private Integer sortOrder;
    /** ACTIVE/DISABLED。 */
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
