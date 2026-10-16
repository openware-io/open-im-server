package io.openware.platform.tenant.domain.pricing.service;

import io.openware.platform.tenant.domain.pricing.model.PricingPlan;
import java.util.List;
import java.util.Optional;

/**
 * 计价方案作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>回答的问题是："某门店、某业态下，每种资源类型最终用哪条计价方案？"
 *
 * <p>三层覆盖，具体优先：
 * <ol>
 *   <li>门店级 + 业态匹配；</li>
 *   <li>租户级 + 业态匹配；</li>
 *   <li>租户级 + 不限业态（兜底）。</li>
 * </ol>
 *
 * <p>解析收在这里而不是写成控制器里的 filter/sorted 链：取错优先级会直接按错误单价计费，
 * 属于必须只有一处口径、且必须可单独验证的规则。
 */
public final class PricingPlanResolver {

  private PricingPlanResolver() {
  }

  /**
   * 从候选方案中挑出目标门店/业态下的生效方案，每种资源类型最多一条。
   *
   * <p>只考虑**生效中**的候选：停用方案不参与覆盖，否则停用一条门店级方案会连带让租户默认也失效
   * （调用方以为"回退到默认"，实际拿到停用的门店级方案）。
   *
   * @param candidates           该租户下的候选方案（通常为全部生效中方案）
   * @param targetStoreId        目标门店
   * @param normalizedBusinessType 目标业态（已归一为大写）
   * @param resourceTypes        需要解析的资源类型，按此顺序输出
   */
  public static List<PricingPlan> resolve(List<PricingPlan> candidates, long targetStoreId,
                                          String normalizedBusinessType, List<String> resourceTypes) {
    return resourceTypes.stream()
        .map(resourceType -> resolveOne(candidates, targetStoreId, normalizedBusinessType, resourceType))
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * 解析单一资源类型的生效方案。
   *
   * <p>同优先级出现多条时不抛异常而是取第一条：作用域唯一键
   * （{@code uk_tnt_pricing_plan_scope_type}）在库层保证同作用域至多一条，
   * 因此这里只可能因调用方传入的候选集不完整或已被并发改写而撞上——那种情况下"取到一条可用方案"
   * 比"整个开台流程失败"更符合业务预期。
   */
  public static Optional<PricingPlan> resolveOne(List<PricingPlan> candidates, long targetStoreId,
                                                 String normalizedBusinessType, String resourceType) {
    return candidates.stream()
        .filter(PricingPlan::active)
        .filter(plan -> resourceType.equalsIgnoreCase(plan.resourceType()))
        .filter(plan -> plan.scopePrecedence(targetStoreId, normalizedBusinessType) >= 0)
        .min(java.util.Comparator.comparingInt(
            plan -> plan.scopePrecedence(targetStoreId, normalizedBusinessType)));
  }
}
