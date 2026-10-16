package io.openware.common.payment.domain.rule.repository;

import io.openware.common.payment.domain.rule.model.ClosingRuleConfig;
import io.openware.common.payment.domain.rule.model.RefundRuleConfig;
import java.util.Optional;

/**
 * 支付规则配置仓储端口（退款规则 + 日结规则）。
 *
 * <p>两个聚合共享一个端口而不是各建一个：它们同属"支付规则配置"这一个概念边界，
 * 生命周期与写入路径（保存即审计）完全一致，拆成两个端口只会让装配多一个依赖而无收益。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PaymentRuleRepository {

  /**
   * 按作用域读取退款规则：租户 + 业态 + 门店层级。
   *
   * <p>{@code storeId} 为 {@code null} 表示租户级，实现必须按 {@code IS NULL} 匹配——
   * SQL 里 {@code = NULL} 永远不成立，用等值比较会把"租户级已存在"误判成"不存在"。
   *
   * <p>只返回**生效中**的行：解析回退要看的是可用配置。
   */
  Optional<RefundRuleConfig> findRefundRule(Long tenantId, String businessType, Long storeId);

  /** 按门店读取生效中的日结规则。 */
  Optional<ClosingRuleConfig> findClosingRule(Long tenantId, Long storeId);

  /** 新增退款规则（版本从 0 起）。 */
  RefundRuleConfig insertRefundRule(RefundRuleConfig config);

  /** 新增日结规则（版本从 0 起）。 */
  ClosingRuleConfig insertClosingRule(ClosingRuleConfig config);

  /**
   * 乐观锁更新退款规则：仅当库中版本等于 {@code expectedVersion} 时写入并自增版本。
   *
   * <p>必须由单条条件 UPDATE 完成：先读出来比对再无条件写回，两个并发写者会双双通过比对，
   * 后写者覆盖前者且双方都收到成功。
   */
  boolean updateRefundRule(RefundRuleConfig config, int expectedVersion);

  /** 乐观锁更新日结规则；语义同 {@link #updateRefundRule}。 */
  boolean updateClosingRule(ClosingRuleConfig config, int expectedVersion);
}
