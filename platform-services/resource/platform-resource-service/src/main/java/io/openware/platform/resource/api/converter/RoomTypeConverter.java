package io.openware.platform.resource.api.converter;

import io.openware.platform.resource.api.dto.RoomTypeView;
import io.openware.platform.resource.domain.roomtype.model.RoomType;

/**
 * 房型领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class RoomTypeConverter {

  private RoomTypeConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static RoomTypeView toView(RoomType roomType) {
    if (roomType == null) {
      return null;
    }
    return new RoomTypeView(roomType.id(), roomType.tenantId(), roomType.storeId(), roomType.code(),
        roomType.name(), roomType.capacity(), roomType.imageUrlsOrEmpty(), roomType.mainImageUrl(),
        roomType.unitPrice(), roomType.serverUnitPrice(), roomType.sortOrder(), roomType.status());
  }
}
