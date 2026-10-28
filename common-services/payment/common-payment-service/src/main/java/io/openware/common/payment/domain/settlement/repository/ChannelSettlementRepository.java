package io.openware.common.payment.domain.settlement.repository;

import io.openware.common.payment.domain.settlement.model.ChannelOrderSnapshot;
import io.openware.common.payment.domain.settlement.model.ChannelSettlement;
import java.util.Optional;

/**
 * 渠道回调结算仓储端口（支付域）。
 *
 * <p>职责只有两件事：
 * <ul>
 *   <li>按 {@code (provider, transactionId, kind)} 读写幂等结算事实；</li>
 *   <li>读取订单的结算口径快照（租户/门店/币种/金额/状态），供 scope 校验与响应回读。</li>
 * </ul>
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或持久化行对象（见 standards/10「端口命名与契约」）。
 * 订单的加锁读取与落账仍走 {@code OrderBillingRepository}：结算台账不替代订单账单端口。
 */
public interface ChannelSettlementRepository {

  /** 按渠道交易 + 结算类型读取幂等结算事实（重复回调命中的唯一判据）。 */
  Optional<ChannelSettlement> findByTransaction(String provider, String transactionId, String kind);

  /** 新增结算事实；唯一键冲突由调用方按并发重复处理。 */
  ChannelSettlement insert(ChannelSettlement settlement);

  /**
   * 只读订单快照（不加锁）：用于解析订单租户/门店并做跨 scope 校验。
   * 订单不存在返回空。
   */
  Optional<ChannelOrderSnapshot> findOrderSnapshot(Long orderId);

  /** 在租户内只读订单快照（加锁后回读最新落账结果，用于响应体）。 */
  Optional<ChannelOrderSnapshot> findOrderSnapshot(Long tenantId, Long orderId);
}
