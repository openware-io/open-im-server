package io.openware.platform.resource.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源（包厢）响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code ResourcePo}，使对外契约与表映射耦合。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy/At、updatedBy/At）。
 *
 * <p>{@code cleaningStartedAt} 保留：它是保洁状态的**业务时间**。
 * 房型快照（roomTypeCode/roomTypeName/roomTypeUnitPrice/roomTypeServerUnitPrice）由查询用例回填。
 *
 * @param imageUrls 图片地址；无图时为空列表（历史数据无图，前端走占位图）
 */
public record ResourceView(
    Long id,
    Long tenantId,
    Long storeId,
    String resourceType,
    String resourceCode,
    String name,
    String areaName,
    Long parentId,
    Integer capacity,
    Long roomTypeId,
    String status,
    String cleaningStatus,
    LocalDateTime cleaningStartedAt,
    List<String> imageUrls,
    String mainImageUrl,
    String description,
    String roomTypeCode,
    String roomTypeName,
    Long roomTypeUnitPrice,
    Long roomTypeServerUnitPrice) {
}
