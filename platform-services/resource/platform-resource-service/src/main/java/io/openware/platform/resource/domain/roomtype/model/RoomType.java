package io.openware.platform.resource.domain.roomtype.model;

import java.util.List;

/**
 * 房型（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：它们是记账信息，由持久化适配器填充。
 *
 * <p>{@code imageUrls} 是**有序图片地址列表**；其在库中的形态是 JSON 列（{@code image_urls}），
 * 这是存储细节，由适配器用类型处理器承担，领域模型只表达"一组图片"。
 *
 * @param id              房型标识
 * @param tenantId        租户
 * @param storeId         门店
 * @param code            房型编码
 * @param name            房型名
 * @param capacity        容量
 * @param imageUrls       图片地址（有序）
 * @param mainImageUrl    主图
 * @param unitPrice       单价（最小货币单位整数）
 * @param serverUnitPrice 服务费单价（最小货币单位整数）
 * @param sortOrder       展示序号
 * @param status          状态
 */
public record RoomType(
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

  /** 房型处于可用状态。取值口径取自建表注释：ENABLED / DISABLED。 */
  public static final String STATUS_ENABLED = "ENABLED";

  /** 是否处于可用状态。 */
  public boolean enabled() {
    return STATUS_ENABLED.equals(status);
  }

  /** 图片地址；为空时返回空列表，避免调用方各自判空。 */
  public List<String> imageUrlsOrEmpty() {
    return imageUrls == null ? List.of() : imageUrls;
  }
}
