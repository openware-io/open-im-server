package io.openware.platform.tenant.api.converter;

import io.openware.platform.tenant.api.dto.TenantView;
import io.openware.platform.tenant.domain.tenant.model.Tenant;

/**
 * 租户领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class TenantConverter {

  private TenantConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static TenantView toView(Tenant tenant) {
    if (tenant == null) {
      return null;
    }
    return new TenantView(tenant.id(), tenant.tenantCode(), tenant.name(), tenant.status(),
        tenant.defaultLocale(), tenant.defaultTimezone(), tenant.createdAt());
  }
}
