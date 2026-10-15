package io.openware.platform.resource.api.dto;

import java.util.List;

/**
 * 房型响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code RoomTypePo}，使对外契约与表映射耦合。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy/At、updatedBy/At）。
 *
 * @param imageUrls 图片地址；无图时为空列表（历史数据无图，前端走占位图）
 */
public record RoomTypeView(
    Long id,
    Long tenantId,
    Long storeId,
    String code,
    String name,
    Integer capacity,
    List<String> imageUrls,
    String mainImageUrl,
    Long unitPrice,
    Long serverUnitPrice,
    Integer sortOrder,
    String status) {
}
