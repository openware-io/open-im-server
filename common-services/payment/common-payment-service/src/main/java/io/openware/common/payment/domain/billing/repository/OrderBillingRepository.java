package io.openware.common.payment.domain.billing.repository;

import io.openware.common.payment.domain.billing.model.OrderReceivable;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 订单账单仓储端口（支付域对订单上下文的受限读/落账面）。
 *
 * <p>按 standards/10“跨上下文读取”，本端口**归属调用方（支付域）**，只暴露收款/退款真正需要的三个动作：
 * 读取应收事实、落账、按账号校验订单归属。订单域自己的写入（改明细、改履约状态）不在其中——
 * 支付域拿不到那些能力。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或持久化行对象（见 standards/10“端口命名与契约”）。
 */
public interface OrderBillingRepository {

  /**
   * 在事务内读取订单应收，并**取行锁**（{@code SELECT ... FOR UPDATE}）。
   *
   * <p><b>锁契约</b>：实现必须使用 {@code FOR UPDATE}，锁在事务提交或回滚前一直持有。
   * 这是"应收校验与服务端落账相对并发收款是原子的"的唯一保证：
   * 本方法的调用方会先用读到的 {@code paidAmount} 校验请求金额、随后用同一个值作为
   * {@link #markPaid} 的期望值；若退化为普通读，两个并发收款会各自读到同一个旧已收金额、
   * 双双通过校验，然后其中一次落账被期望值条件拦下——用户已经付了钱却收到冲突。
   *
   * @return 订单应收；订单不存在时为空（调用方按"无账单可校验"处理）
   */
  Optional<OrderReceivable> findForUpdate(Long tenantId, Long orderId);

  /**
   * 收款落账：把已收金额从 {@code expectedPaid} 推到 {@code newPaid}，足额则置为完成。
   *
   * <p><b>返回值必须是"恰好更新了一行"</b>：{@code false} 表示期望的已收金额已不成立、
   * 或订单已进入取消/作废/完成，调用方据此报 409 冲突并回滚整笔收款。
   * 把返回值弱化成 void 会让"订单已被其他收款操作修改"变成静默覆盖。
   *
   * <p>{@code refundableAmount} 与已收金额同步（可退 = 已收）：不同步会让未收款订单的
   * "可退金额"与实收金额口径不一致。
   *
   * @param expectedPaid 期望的已收金额（乐观条件，非空）
   * @param newPaid      落账后的已收金额
   * @param totalAmount  应收总额（判定是否足额完成）
   */
  /** 读取订单所属门店，用于收款/退款的门店范围校验。 */
  Optional<Long> findStoreId(Long tenantId, Long orderId);

  boolean markPaid(Long tenantId, Long orderId, BigDecimal expectedPaid, BigDecimal newPaid,
                   BigDecimal totalAmount);

  /**
   * 按账号校验订单归属，返回该订单绑定的会员标识。
   *
   * <p>用于 C 端：调用方拿到的 {@code accountId} 必须确实是该订单的会员，否则不能替它发起积分/储值抵扣。
   * 订单不存在、或订单的会员不属于该账号时返回空——两种情况对调用方都是"不能动这个订单"，
   * 不需要区分（区分反而会给越权探测提供信号）。
   */
  Optional<Long> findOwnedCustomerId(Long tenantId, Long orderId, Long accountId);

  /**
   * 读取订单币种快照（只读、无锁）。
   *
   * <p>退款路径专用：退款必须退**原币种**（跨币种退款是合规禁区，见 16_CURRENCY_CONVENTIONS §6.3），
   * 因此需要在建退款单时拿到订单当时的币种。
   *
   * <p>与 {@link #findForUpdate} 分开而不是复用它：退款建单不需要锁，也不该因为读取币种
   * 就去锁住订单主行——那会让退款申请与收款互相阻塞。订单不存在时返回空（调用方按缺省币种处理）。
   */
  Optional<String> findCurrencyCode(Long tenantId, Long orderId);

  /** 退款落账：回冲订单已收与可退金额；金额不得小于 0。 */
  boolean markRefunded(Long tenantId, Long orderId, BigDecimal refundAmount);

}
