package io.openware.platform.resource.domain.resource.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源（领域模型）：门店内的可占用实体，例如 KTV 包厢、桌台、车位。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：它们是记账信息，由持久化适配器填充。
 * 业务时间 {@code cleaningStartedAt} 保留。
 *
 * <p>{@code roomTypeCode}/{@code roomTypeName}/{@code roomTypeUnitPrice}/{@code roomTypeServerUnitPrice}
 * 是**联表带出的只读快照**（建表无此列，由查询 join 房型得到）。作为领域模型的一部分保留，
 * 因为它们参与对外展示与计价口径；适配器负责从投影填充。
 *
 * @param id                    资源标识
 * @param tenantId              租户
 * @param storeId               门店
 * @param resourceType          资源类型编码
 * @param resourceCode          资源编码
 * @param name                  名称
 * @param areaName              区域名
 * @param parentId              父资源
 * @param capacity              容量
 * @param roomTypeId            关联房型
 * @param status                状态：ENABLED / DISABLED / MAINTENANCE
 * @param cleaningStatus        保洁状态
 * @param cleaningStartedAt     保洁开始时间
 * @param imageUrls             图片地址
 * @param mainImageUrl          主图
 * @param description           描述
 * @param roomTypeCode          房型编码（联表快照）
 * @param roomTypeName          房型名（联表快照）
 * @param roomTypeUnitPrice     房型单价（联表快照）
 * @param roomTypeServerUnitPrice 房型服务费单价（联表快照）
 */
public record Resource(
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

  /** 资源处于可用状态。取值口径取自建表注释：ENABLED / DISABLED / MAINTENANCE。 */
  public static final String STATUS_ENABLED = "ENABLED";

  /** 是否处于可用状态。 */
  public boolean enabled() {
    return STATUS_ENABLED.equals(status);
  }

  /** 是否关联房型（决定是否需要带出房型快照）。 */
  public boolean hasRoomType() {
    return roomTypeId != null;
  }

  /** 图片地址；为空时返回空列表，避免调用方各自判空（历史数据无图）。 */
  public List<String> imageUrlsOrEmpty() {
    return imageUrls == null ? List.of() : imageUrls;
  }

  /** 保洁状态：空闲。 */
  public static final String CLEANING_IDLE = "IDLE";

  /** 保洁状态：清洁中（清洁中不可开台、不可预约）。 */
  public static final String CLEANING_IN_PROGRESS = "CLEANING";

  /** 当前是否处于清洁中。 */
  public boolean cleaning() {
    return CLEANING_IN_PROGRESS.equals(cleaningStatus);
  }

  /**
   * 迁移保洁状态：返回新实例；状态本来就一致时**原样返回本实例**，供调用方据此跳过无意义的写库。
   *
   * <p>保洁开始时间与状态是同一个事实的两面，由本方法一并维护：进入清洁中记开始时间，结束清洁清空它。
   * 这样调用方不必自己记得清理该字段，也就不会留下"空闲但仍有清洁开始时间"的不一致记录。
   */
  public Resource withCleaning(boolean startCleaning, LocalDateTime moment) {
    if (cleaning() == startCleaning) {
      return this;
    }
    return new Resource(id, tenantId, storeId, resourceType, resourceCode, name, areaName, parentId,
        capacity, roomTypeId, status,
        startCleaning ? CLEANING_IN_PROGRESS : CLEANING_IDLE,
        startCleaning ? moment : null,
        imageUrls, mainImageUrl, description, roomTypeCode, roomTypeName, roomTypeUnitPrice,
        roomTypeServerUnitPrice);
  }
}
