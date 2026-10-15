package io.openware.platform.resource.api.converter;

import io.openware.platform.resource.api.dto.OccupationView;
import io.openware.platform.resource.domain.occupation.model.Occupation;

/**
 * 占用领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class OccupationConverter {

  private OccupationConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static OccupationView toView(Occupation occupation) {
    if (occupation == null) {
      return null;
    }
    return new OccupationView(occupation.id(), occupation.tenantId(), occupation.storeId(),
        occupation.resourceId(), occupation.sourceType(), occupation.sourceId(), occupation.startAt(),
        occupation.endAt(), occupation.status(), occupation.holdExpiresAt(), occupation.version());
  }
}
