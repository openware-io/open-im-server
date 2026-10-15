package io.openware.platform.resource.api.converter;

import io.openware.platform.resource.api.dto.ResourceView;
import io.openware.platform.resource.domain.resource.model.Resource;

/**
 * 资源领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class ResourceConverter {

  private ResourceConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static ResourceView toView(Resource resource) {
    if (resource == null) {
      return null;
    }
    return new ResourceView(resource.id(), resource.tenantId(), resource.storeId(), resource.resourceType(),
        resource.resourceCode(), resource.name(), resource.areaName(), resource.parentId(),
        resource.capacity(), resource.roomTypeId(), resource.status(), resource.cleaningStatus(),
        resource.cleaningStartedAt(), resource.imageUrlsOrEmpty(), resource.mainImageUrl(),
        resource.description(), resource.roomTypeCode(), resource.roomTypeName(),
        resource.roomTypeUnitPrice(), resource.roomTypeServerUnitPrice());
  }
}
