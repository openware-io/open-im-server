package io.openware.platform.tenant.api.converter;

import io.openware.platform.tenant.api.dto.PricingPlanView;
import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.domain.pricing.repository.Persisted;

/**
 * 计价方案领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class PricingPlanConverter {

  private PricingPlanConverter() {
  }

  /** 领域模型转对外视图；入参为空时返回空。 */
  public static PricingPlanView toView(Persisted<PricingPlan> persisted) {
    if (persisted == null || persisted.aggregate() == null) {
      return null;
    }
    PricingPlan plan = persisted.aggregate();
    return new PricingPlanView(plan.id(), plan.tenantId(), plan.storeId(), plan.businessType(),
        plan.resourceType(), plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(),
        plan.pricePerUnit(), plan.defaultSessionMinutes(), plan.overtimeRate(), plan.status(),
        plan.version(), plan.idempotencyKey(),
        persisted.audit() == null ? null : persisted.audit().createdAt(),
        persisted.audit() == null ? null : persisted.audit().updatedAt());
  }
}
