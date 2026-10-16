package io.openware.common.payment.domain.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.payment.domain.rule.model.RefundRuleConfig;
import io.openware.common.payment.domain.rule.service.RefundRuleResolver;
import io.openware.common.payment.domain.rule.service.RefundRuleResolver.ResolvedRefundRule;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 退款规则作用域解析（纯领域，无 Spring、无持久化）。
 *
 * <p>规则此前内联在应用服务里，只能通过构造服务 + mock 两个 mapper 才能验证；提取后可以直接穷举每一层组合。
 * 其中"线下退款开关不看门店行"是资金策略边界，必须单独锁死。
 */
class RefundRuleResolverTest {

  private static final long TENANT = 100L;

  @Test
  void storeRowOverridesThresholdAndIsReportedAsStore() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.of(rule(1L, "KTV", 10L, "500", true)),
        Optional.of(rule(2L, "KTV", null, "900", false)),
        Optional.of(rule(3L, "", null, "100", false)));

    assertEquals(new BigDecimal("500"), result.approvalThreshold());
    assertEquals("STORE", result.source(), "门店级命中时来源必须是 STORE");
  }

  @Test
  void businessRowUsedWhenNoStoreRow() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.empty(),
        Optional.of(rule(2L, "KTV", null, "900", false)),
        Optional.of(rule(3L, "", null, "100", false)));

    assertEquals(new BigDecimal("900"), result.approvalThreshold());
    assertEquals("BUSINESS", result.source());
  }

  @Test
  void tenantRowUsedAsFallback() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.empty(), Optional.empty(),
        Optional.of(rule(3L, "", null, "100", false)));

    assertEquals(new BigDecimal("100"), result.approvalThreshold());
    assertEquals("TENANT", result.source());
  }

  @Test
  void noRowsYieldsDefault() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(Optional.empty(), Optional.empty(), Optional.empty());

    assertEquals(BigDecimal.ZERO, result.approvalThreshold());
    assertEquals("DEFAULT", result.source());
    assertEquals(0, result.version());
    assertTrue(result.offlineRefundEnabled(), "缺省线下退款为开启（与既有行为一致）");
  }

  /**
   * 资金策略边界：线下退款开关**只**来自业态/租户层。
   *
   * <p>门店行即使显式关闭，也不能影响生效值——否则门店页面可以通过门店行打开线下退款，
   * 绕过总部策略。这里门店行给"关闭"、业态行给"开启"，结果必须是开启且不受门店行干扰。
   */
  @Test
  void offlineRefundSwitchIgnoresStoreRow() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.of(rule(1L, "KTV", 10L, "500", false)),
        Optional.of(rule(2L, "KTV", null, "900", true)),
        Optional.of(rule(3L, "", null, "100", false)));

    // 阈值取门店行
    assertEquals(new BigDecimal("500"), result.approvalThreshold());
    // 开关取业态行（开启），**不是**门店行的关闭
    assertTrue(result.offlineRefundEnabled(), "门店行不得决定线下退款开关");
  }

  /** 业态行缺失时线下退款开关回退到租户行。 */
  @Test
  void offlineRefundSwitchFallsBackToTenantRow() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.of(rule(1L, "KTV", 10L, "500", false)),
        Optional.empty(),
        Optional.of(rule(3L, "", null, "100", false)));

    assertFalse(result.offlineRefundEnabled(), "业态行缺失时取租户行的关闭值");
  }

  /** 开关字段为空视为开启（缺省不收紧），只有显式 false 才关闭。 */
  @Test
  void nullOfflineSwitchTreatedAsEnabled() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.empty(),
        Optional.of(rule(2L, "KTV", null, "900", null)),
        Optional.empty());

    assertTrue(result.offlineRefundEnabled());
  }

  /** 版本号来自阈值来源行，调用方据此做后续 CAS。 */
  @Test
  void versionComesFromThresholdSourceRow() {
    ResolvedRefundRule result = RefundRuleResolver.resolve(
        Optional.empty(),
        Optional.of(rule(2L, "KTV", null, "900", true, 7)),
        Optional.of(rule(3L, "", null, "100", true, 3)));

    assertEquals(7, result.version());
  }

  private static RefundRuleConfig rule(long id, String businessType, Long storeId, String threshold,
                                       Boolean offline) {
    return rule(id, businessType, storeId, threshold, offline, 0);
  }

  private static RefundRuleConfig rule(long id, String businessType, Long storeId, String threshold,
                                       Boolean offline, int version) {
    return new RefundRuleConfig(id, TENANT, businessType, storeId, new BigDecimal(threshold), offline,
        version, null, RefundRuleConfig.STATUS_ACTIVE);
  }
}
