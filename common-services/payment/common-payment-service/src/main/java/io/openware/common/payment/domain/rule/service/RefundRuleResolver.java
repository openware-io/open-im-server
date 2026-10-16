package io.openware.common.payment.domain.rule.service;

import io.openware.common.payment.domain.rule.model.RefundRuleConfig;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 退款规则的作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>两条取值路径**刻意不同**，这是本类存在的全部理由：
 * <ul>
 *   <li><b>审批阈值</b>：门店覆盖 &gt; 业态默认 &gt; 租户默认；</li>
 *   <li><b>线下退款开关</b>：只看业态默认 &gt; 租户默认，**门店行不参与**。</li>
 * </ul>
 *
 * <p>第二条是资金策略边界：线下退款意味着钱在系统外离开，若允许门店行决定它，
 * 门店页面就能自行打开线下退款、绕过总部策略。因此门店行即使带有该字段（统一安全默认值）也不被读取。
 *
 * <p>方法签名是三个"候选行"而不是一张全量列表：每一层各自按自己的键查询，
 * "传入的是哪一层"由调用方决定，解析器只负责层间顺序与来源判定。
 */
public final class RefundRuleResolver {

  private RefundRuleResolver() {
  }

  /**
   * 解析结果。
   *
   * @param approvalThreshold    生效的审批阈值
   * @param offlineRefundEnabled 生效的线下退款开关
   * @param source               阈值来自哪一层：{@code STORE} / {@code BUSINESS} / {@code TENANT} / {@code DEFAULT}
   * @param version              生效行的版本（无行时为 0）
   */
  public record ResolvedRefundRule(
      BigDecimal approvalThreshold,
      boolean offlineRefundEnabled,
      String source,
      Integer version) {
  }

  /** 无任何配置时的缺省：阈值 0（不拦）、线下退款开启、来源 DEFAULT。 */
  public static final ResolvedRefundRule DEFAULT =
      new ResolvedRefundRule(BigDecimal.ZERO, true, "DEFAULT", 0);

  /**
   * 按门店与业态解析生效的退款规则。
   *
   * @param storeRow    {@code (租户, 业态, 门店)} 这一层；调用方无门店时传空
   * @param businessRow {@code (租户, 业态, 租户级)} 这一层
   * @param tenantRow   {@code (租户, 不限业态, 租户级)} 这一层（兜底）
   */
  public static ResolvedRefundRule resolve(Optional<RefundRuleConfig> storeRow,
                                           Optional<RefundRuleConfig> businessRow,
                                           Optional<RefundRuleConfig> tenantRow) {
    // 阈值：门店覆盖 > 业态默认 > 租户默认（三个候选行按此顺序，谁先命中用谁）
    Optional<RefundRuleConfig> thresholdRow = storeRow.isPresent() ? storeRow
        : (businessRow.isPresent() ? businessRow : tenantRow);
    if (thresholdRow.isEmpty()) {
      return DEFAULT;
    }
    RefundRuleConfig row = thresholdRow.get();

    // 线下退款开关：门店行**不参与**（见类注释）。这里刻意用 businessRow/tenantRow 两个候选，
    // 而不是复用上面选中的 row——复用会在门店级命中时把门店行当成开关来源。
    Optional<RefundRuleConfig> offlineRow = businessRow.isPresent() ? businessRow : tenantRow;
    boolean offline = offlineRow
        .map(config -> !Boolean.FALSE.equals(config.offlineRefundEnabled()))
        .orElse(true);

    return new ResolvedRefundRule(row.approvalThreshold(), offline, sourceOf(row), row.version());
  }

  /** 阈值来源：门店级 → STORE；限定业态 → BUSINESS；否则 TENANT。 */
  private static String sourceOf(RefundRuleConfig row) {
    if (row.storeLevel()) {
      return "STORE";
    }
    return row.businessTypeSpecific() ? "BUSINESS" : "TENANT";
  }
}
