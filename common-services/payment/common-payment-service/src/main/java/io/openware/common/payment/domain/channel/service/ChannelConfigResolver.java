package io.openware.common.payment.domain.channel.service;

import io.openware.common.payment.domain.channel.model.ChannelConfig;
import java.util.List;
import java.util.Optional;

/**
 * 支付渠道配置的作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>门店读取按 <b>门店覆盖 > 业态默认 > 租户默认</b> 解析：
 * <ol>
 *   <li>{@code 0} 门店级且业态匹配；</li>
 *   <li>{@code 1} 租户级且业态匹配；</li>
 *   <li>{@code 2} 租户级且不限业态（兜底）。</li>
 * </ol>
 *
 * <p>这条顺序决定"某门店到底能不能走线上收款"，取错会让未开通的渠道被判为已开通（或反之），
 * 因此必须只有一处口径且可单独验证——原先它内联在服务里的 filter/sorted 闭包中。
 */
public final class ChannelConfigResolver {

  private ChannelConfigResolver() {
  }

  /**
   * 在目标门店/业态下解析单个渠道的生效配置。
   *
   * <p><b>不按 {@code enabled} 过滤</b>：本方法是纯粹的作用域解析。
   * 配置列表需要显示"已关闭"的渠道（运营要能把它重新打开），而收款可用性判定才关心开关值；
   * 若在这里过滤掉未开通的配置，列表在指定门店时会凭空少几条，运营无法再开启它们。
   * 调用方按用途自行检查 {@link ChannelConfig#enabledForUse()}。
   */
  public static Optional<ChannelConfig> resolve(List<ChannelConfig> candidates, String channel,
                                                Long storeId, String normalizedBusinessType) {
    return candidates.stream()
        .filter(config -> channel.equals(config.channel()))
        .filter(config -> scopePrecedence(config, storeId, normalizedBusinessType) >= 0)
        .min(java.util.Comparator.comparingInt(
            config -> scopePrecedence(config, storeId, normalizedBusinessType)));
  }

  /**
   * 候选配置在目标门店/业态下的优先级，数字越小越优先；不适用时返回 {@code -1}。
   *
   * <p>作用域唯一键在库层保证同作用域至多一条，因此同优先级撞车只可能来自并发改写；
   * 那种情况下取任意一条可用配置，比让整个收款流程失败更符合业务预期。
   */
  public static int scopePrecedence(ChannelConfig config, Long storeId, String normalizedBusinessType) {
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
}
