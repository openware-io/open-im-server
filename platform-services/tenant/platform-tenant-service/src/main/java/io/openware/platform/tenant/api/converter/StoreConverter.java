package io.openware.platform.tenant.api.converter;

import io.openware.platform.tenant.api.dto.StoreView;
import io.openware.platform.tenant.domain.store.model.Store;

/**
 * 门店领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class StoreConverter {

  private StoreConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static StoreView toView(Store store) {
    if (store == null) {
      return null;
    }
    return new StoreView(store.id(), store.tenantId(), store.organizationId(), store.code(),
        store.name(), store.businessType(), store.countryCode(), store.regionCode(), store.timezone(),
        store.defaultCurrency(), store.locale(), store.taxProfileId(), store.businessDayCutoff(),
        store.status(), store.logoUrl());
  }
}
