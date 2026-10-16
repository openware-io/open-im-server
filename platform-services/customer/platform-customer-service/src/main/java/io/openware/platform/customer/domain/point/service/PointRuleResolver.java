package io.openware.platform.customer.domain.point.service;

import io.openware.platform.customer.domain.point.model.PointRuleConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 积分规则作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>生效顺序：<b>门店覆盖 &gt; 业态默认 &gt; 租户默认</b>。
 *
 * <p>取错层级会按错误的倍率记分或抵扣，因此这条顺序必须只有一处口径且可单独验证——
 * 原先它是一段三层嵌套的 {@code orElseGet} 链，只有通过 mock mapper 才能触达。
 */
public final class PointRuleResolver {

  private PointRuleResolver() {
  }

  /**
   * 从候选配置中挑出生效的一条；没有可用配置时返回空。
   *
   * <p>只考虑**生效中**的候选：停用配置不参与覆盖，否则停掉一条门店级规则会让调用方以为"回退到默认"，
   * 实际仍命中那条停用规则。
   *
   * @param candidates           该租户下的候选配置（通常为全部生效中配置）
   * @param storeId              目标门店；{@code null} 或 0 表示只看租户/业态层
   * @param normalizedBusinessType 目标业态（已归一为大写）；{@code null} 表示未指定
   */
  public static Optional<PointRuleConfig> resolve(List<PointRuleConfig> candidates, Long storeId,
                                                 String normalizedBusinessType) {
    return candidates.stream()
        .filter(PointRuleConfig::active)
        .filter(config -> precedence(config, storeId, normalizedBusinessType) >= 0)
        .min(java.util.Comparator.comparingInt(
            config -> precedence(config, storeId, normalizedBusinessType)));
  }

  /**
   * 候选配置的优先级，数字越小越优先；不适用时返回 {@code -1}。
   *
   * <p>三档：
   * <ol>
   *   <li>{@code 0} 门店级且业态匹配——最具体的覆盖；</li>
   *   <li>{@code 1} 租户级且业态匹配——该业态的默认；</li>
   *   <li>{@code 2} 租户级且不限业态——兜底默认。</li>
   * </ol>
   *
   * <p>限定业态的租户级配置**不能**匹配未指定业态的查询（不是兜底）；反之不限业态的配置可以匹配
   * 任意指定的业态（它就是为兜底而存在）。这两条不对称是刻意的。
   */
  public static int precedence(PointRuleConfig config, Long storeId, String normalizedBusinessType) {
    if (config.storeLevel()) {
      return storeId != null && storeId.equals(config.storeId())
          && normalizedBusinessType != null && config.hasBusinessType(normalizedBusinessType)
          ? 0 : -1;
    }
    if (normalizedBusinessType != null && config.hasBusinessType(normalizedBusinessType)) {
      return 1;
    }
    return config.businessTypeSpecific() ? -1 : 2;
  }

  /** 缺省规则：倍率 1、不过期、不限抵扣上限。 */
  public static PointRuleConfig defaultRule() {
    return new PointRuleConfig(null, null, PointRuleConfig.ANY_BUSINESS_TYPE,
        PointRuleConfig.TENANT_LEVEL_STORE_ID, PointRuleConfig.DEFAULT_RATE,
        PointRuleConfig.DEFAULT_RATE, 0, 0L, 0, null, PointRuleConfig.STATUS_ACTIVE);
  }

  /** 有效获取倍率（为空时取缺省）。 */
  public static BigDecimal earnRateOrDefault(PointRuleConfig config) {
    return config == null || config.earnRate() == null ? PointRuleConfig.DEFAULT_RATE
        : config.earnRate();
  }

  /** 有效抵扣倍率（为空时取缺省）。 */
  public static BigDecimal redeemRateOrDefault(PointRuleConfig config) {
    return config == null || config.redeemRate() == null ? PointRuleConfig.DEFAULT_RATE
        : config.redeemRate();
  }
}
