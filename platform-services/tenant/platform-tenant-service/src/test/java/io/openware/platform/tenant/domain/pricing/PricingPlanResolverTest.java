package io.openware.platform.tenant.domain.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import io.openware.platform.tenant.domain.pricing.service.PricingPlanResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 计价方案作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>取错优先级会直接按错误单价计费，因此这里把三层覆盖的每种组合都固定下来。
 */
class PricingPlanResolverTest {

  private static final long TENANT = 100L;
  private static final long STORE = 10L;

  @Test
  void storeLevelBeatsBusinessDefaultAndTenantDefault() {
    PricingPlan storePlan = plan(1L, STORE, "KTV", 500L);
    PricingPlan businessDefault = plan(2L, 0L, "KTV", 400L);
    PricingPlan tenantDefault = plan(3L, 0L, "", 300L);

    var resolved = PricingPlanResolver.resolveOne(
        List.of(tenantDefault, businessDefault, storePlan), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertEquals(500L, resolved.orElseThrow().pricePerUnit(), "门店级覆盖优先");
  }

  @Test
  void businessDefaultBeatsTenantDefault() {
    PricingPlan businessDefault = plan(2L, 0L, "KTV", 400L);
    PricingPlan tenantDefault = plan(3L, 0L, "", 300L);

    var resolved = PricingPlanResolver.resolveOne(
        List.of(tenantDefault, businessDefault), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertEquals(400L, resolved.orElseThrow().pricePerUnit(), "业态默认优先于不限业态的租户默认");
  }

  @Test
  void tenantDefaultUsedAsFallback() {
    var resolved = PricingPlanResolver.resolveOne(
        List.of(plan(3L, 0L, "", 300L)), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertEquals(300L, resolved.orElseThrow().pricePerUnit());
  }

  /** 别的门店的门店级方案与本店无关，不得参与。 */
  @Test
  void otherStoresPlanDoesNotApply() {
    var resolved = PricingPlanResolver.resolveOne(
        List.of(plan(1L, 99L, "KTV", 500L)), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertTrue(resolved.isEmpty(), "别的门店的覆盖不应被本店命中");
  }

  /** 限定了别的业态的方案对本业态不适用（既不是业态默认、也不是兜底）。 */
  @Test
  void otherBusinessTypePlanDoesNotApply() {
    var resolved = PricingPlanResolver.resolveOne(
        List.of(plan(1L, 0L, "RETAIL", 500L)), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertTrue(resolved.isEmpty(), "限定 RETAIL 的方案不该被 KTV 命中");
  }

  /**
   * 停用方案不参与覆盖：否则停掉一条门店级方案，会让调用方以为"回退到业态默认"，
   * 实际仍命中那条停用的门店级方案。
   */
  @Test
  void disabledPlanDoesNotParticipateSoResolutionFallsBack() {
    PricingPlan disabledStorePlan = disabled(plan(1L, STORE, "KTV", 500L));
    PricingPlan businessDefault = plan(2L, 0L, "KTV", 400L);

    var resolved = PricingPlanResolver.resolveOne(
        List.of(disabledStorePlan, businessDefault), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM);

    assertEquals(400L, resolved.orElseThrow().pricePerUnit(), "停用的门店级方案必须让位给业态默认");
  }

  @Test
  void noCandidateResolvesToEmpty() {
    assertTrue(PricingPlanResolver.resolveOne(List.of(), STORE, "KTV", PricingPlan.RESOURCE_KTV_ROOM).isEmpty());
  }

  /** 每种资源类型各解析一条：包厢与服务人员的方案互相独立。 */
  @Test
  void resolvesOnePerResourceTypeInRequestedOrder() {
    PricingPlan room = plan(1L, 0L, "KTV", 500L);
    PricingPlan server = withResource(plan(2L, 0L, "KTV", 200L), PricingPlan.RESOURCE_KTV_SERVER);

    var resolved = PricingPlanResolver.resolve(List.of(server, room), STORE, "KTV",
        List.of(PricingPlan.RESOURCE_KTV_ROOM, PricingPlan.RESOURCE_KTV_SERVER));

    assertEquals(2, resolved.size());
    assertEquals(PricingPlan.RESOURCE_KTV_ROOM, resolved.get(0).resourceType());
    assertEquals(500L, resolved.get(0).pricePerUnit());
    assertEquals(PricingPlan.RESOURCE_KTV_SERVER, resolved.get(1).resourceType());
    assertEquals(200L, resolved.get(1).pricePerUnit());
  }

  /** 资源类型匹配大小写不敏感（库里可能存小写，调用方传大写常量）。 */
  @Test
  void resourceTypeMatchingIsCaseInsensitive() {
    PricingPlan lower = withResource(plan(1L, 0L, "KTV", 500L), "ktv_room");

    assertTrue(PricingPlanResolver.resolveOne(List.of(lower), STORE, "KTV",
        PricingPlan.RESOURCE_KTV_ROOM).isPresent());
  }

  // ---------- 夹具 ----------

  private static PricingPlan plan(long id, long storeId, String businessType, long pricePerUnit) {
    return new PricingPlan(id, TENANT, storeId, businessType, PricingPlan.RESOURCE_KTV_ROOM, "HOUR", 30,
        "ROUND_UP", pricePerUnit, 120, null, PricingPlan.STATUS_ACTIVE, 0, null, null, null);
  }

  private static PricingPlan withResource(PricingPlan plan, String resourceType) {
    return new PricingPlan(plan.id(), plan.tenantId(), plan.storeId(), plan.businessType(), resourceType,
        plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(), plan.pricePerUnit(),
        plan.defaultSessionMinutes(), plan.overtimeRate(), plan.status(), plan.version(),
        plan.idempotencyKey(), plan.createdAt(), plan.updatedAt());
  }

  private static PricingPlan disabled(PricingPlan plan) {
    return new PricingPlan(plan.id(), plan.tenantId(), plan.storeId(), plan.businessType(),
        plan.resourceType(), plan.billingUnit(), plan.incrementMinutes(), plan.roundingDirection(),
        plan.pricePerUnit(), plan.defaultSessionMinutes(), plan.overtimeRate(), PricingPlan.STATUS_DISABLED,
        plan.version(), plan.idempotencyKey(), plan.createdAt(), plan.updatedAt());
  }
}
