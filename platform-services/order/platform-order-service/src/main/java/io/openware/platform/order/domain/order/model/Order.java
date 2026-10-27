package io.openware.platform.order.domain.order.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 订单聚合（领域模型）。
 *
 * <p><b>只承载 {@code ord_order} 的真实列</b>。原持久化对象 {@code OrderPo} 上另有若干
 * {@code @TableField(exist = false)} 字段（sessionId / roomResourceId / roomName / roomCode / sessionStatus /
 * partySize / serverId / serverName / roomElapsedSeconds / roomEstimatedFee / liveTotalAmount /
 * sessionOpenedAt / sessionClosedAt）——它们是**从 {@code ord_ktv_session} 联表读出的投影**，
 * 不是订单自身的业务事实。它们住在 {@code application.dto.OrderView} 里，<b>不进领域模型</b>：
 * 领域模型一旦携带它们，任何整聚合写入都会试图为不存在的列生成引用，运行时抛
 * {@code can not find lambda cache for this property}，而编译期完全看不出来。
 *
 * <p><b>交易状态与业态状态分离</b>（{@code ORDER_ROOM_INVENTORY_TARGET.md} §2）：
 * 订单只表达 {@link #tradeStatus}（{@link TradeStatus}）与 {@link #refundStatus}（{@link RefundStatus}）；
 * KTV 的开台中/挂单/已结台是 {@code ord_ktv_session.status}（见 {@code domain.ktv.model.KtvSessionStatus}），
 * 酒店的在住/待退是 {@code htl_booking.status}。{@link #status} 是**收口前的兼容列**：
 * 过渡期与旧读取方（payment-service 的应收校验、旧报表）同步写入，最终清理迁移会删除它；
 * 任何新逻辑都不允许以它为判定依据。
 *
 * <p>审计字段（createdBy / createdAt / updatedBy / updatedAt）同样不在此承载：它们是记账信息，
 * 需要时由适配器读回并随视图传递（见 catalog 的 {@code Persisted<T>}）。
 *
 * <p>{@code version} 保留：它参与乐观锁判定。注意 {@code OrderPo} 上<b>没有</b> {@code @Version} 注解、
 * 工程里也<b>没有</b>乐观锁拦截器，所以 {@code updateById} 不会附加版本条件——金额与状态写入必须走
 * {@link io.openware.platform.order.domain.order.repository.OrderRepository} 上具名的条件方法。
 *
 * @param id               订单标识
 * @param tenantId         租户
 * @param organizationId   组织（集团/公司）维度
 * @param storeId          门店
 * @param orderNo          订单号（租户内唯一，日序列生成）
 * @param idempotencyKey   请求幂等键（快速开台重试命中同一键时回放已有订单）
 * @param businessType     业态
 * @param customerId       客户档案
 * @param status           **兼容列**（{@code ord_order.status}，收口前与 tradeStatus 双写；不得作为新逻辑判据）
 * @param tradeStatus      交易状态（DRAFT/PENDING_PAYMENT/PAID/COMPLETED/CANCELLED，见 {@link TradeStatus}）
 * @param refundStatus     退款状态（NONE/PARTIAL/FULL，见 {@link RefundStatus}）
 * @param currencyCode     币种快照
 * @param subtotalAmount   原价合计
 * @param discountAmount   优惠合计
 * @param taxAmount        税额
 * @param totalAmount      应付合计
 * @param paidAmount       已收金额
 * @param refundableAmount 可退金额
 * @param completedAt      完成时间
 * @param cancelledAt      作废/取消时间
 * @param holdReason       挂单原因（挂单是开台中的标记，不改交易状态）
 * @param holdAt           挂单时间
 * @param version          乐观锁版本号
 */
public record Order(
    Long id,
    Long tenantId,
    Long organizationId,
    Long storeId,
    String orderNo,
    String idempotencyKey,
    String businessType,
    Long customerId,
    String status,
    String tradeStatus,
    String refundStatus,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal discountAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal refundableAmount,
    LocalDateTime completedAt,
    LocalDateTime cancelledAt,
    String holdReason,
    LocalDateTime holdAt,
    Integer version) {

  /** 终态集合（交易状态）：处于这些状态的订单不可再取消/作废/加项。 */
  private static final Set<TradeStatus> TERMINAL_TRADE_STATUSES =
      Set.of(TradeStatus.COMPLETED, TradeStatus.CANCELLED, TradeStatus.VOIDED);

  /**
   * 兼容构造：旧调用方（过渡期测试/内部装配）只给 {@code ord_order.status}。
   *
   * <p>交易/退款状态按旧状态推导（{@link TradeStatus#fromLegacy(String)} /
   * {@link RefundStatus#fromLegacy(String)}），保证旧调用点不会写出「有状态、无交易状态」的半行数据。
   * 新代码应使用规范构造并显式给出 tradeStatus。
   */
  public Order(Long id, Long tenantId, Long organizationId, Long storeId, String orderNo,
               String idempotencyKey, String businessType, Long customerId, String status,
               String currencyCode, BigDecimal subtotalAmount, BigDecimal discountAmount,
               BigDecimal taxAmount, BigDecimal totalAmount, BigDecimal paidAmount,
               BigDecimal refundableAmount, LocalDateTime completedAt, LocalDateTime cancelledAt,
               String holdReason, LocalDateTime holdAt, Integer version) {
    this(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType, customerId, status,
        TradeStatus.fromLegacy(status).name(), RefundStatus.fromLegacy(status).name(), currencyCode,
        subtotalAmount, discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt,
        cancelledAt, holdReason, holdAt, version);
  }

  /** 交易状态（强类型；列值缺失/脏数据时按旧 status 推导，绝不返回 null）。 */
  public TradeStatus trade() {
    if (tradeStatus != null && !tradeStatus.isBlank()) {
      return TradeStatus.parse(tradeStatus);
    }
    return TradeStatus.fromLegacy(status);
  }

  /** 退款状态（强类型；列值缺失/脏数据时按旧 status 推导）。 */
  public RefundStatus refund() {
    if (refundStatus != null && !refundStatus.isBlank()) {
      return RefundStatus.parse(refundStatus);
    }
    return RefundStatus.fromLegacy(status);
  }

  /** 是否为交易终态（COMPLETED/CANCELLED）。 */
  public boolean terminal() {
    return TERMINAL_TRADE_STATUSES.contains(trade());
  }

  /** 是否已取消/作废。 */
  public boolean voided() {
    return trade() == TradeStatus.VOIDED;
  }

  /** 是否处于挂单中。 */
  public boolean held() {
    return holdAt != null;
  }

  /** 版本号；为空视为 0。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }

  /** 金额按"空视为 0"读取，避免调用方各自判空。 */
  public BigDecimal subtotal() {
    return subtotalAmount == null ? BigDecimal.ZERO : subtotalAmount;
  }

  /** 应付合计（空视为 0）。 */
  public BigDecimal total() {
    return totalAmount == null ? BigDecimal.ZERO : totalAmount;
  }

  /** 已收金额（空视为 0）。 */
  public BigDecimal paid() {
    return paidAmount == null ? BigDecimal.ZERO : paidAmount;
  }

  /** 可退金额（空视为 0）。 */
  public BigDecimal refundable() {
    return refundableAmount == null ? BigDecimal.ZERO : refundableAmount;
  }

  /** 兼容列的状态转换（返回新实例）：只改 {@code ord_order.status}，交易/退款状态保持原值。 */
  public Order withStatus(String newStatus) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, newStatus, tradeStatus, refundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt,
        version);
  }

  /**
   * 交易状态转换（返回新实例）：**同时**写交易状态与兼容列 {@code status}（双写，收口前口径）。
   *
   * <p>{@code newLegacyStatus} 由调用方按业态给出（KTV：开台中 SERVING、结台待收款 WAITING_SETTLEMENT…），
   * 因为旧列把「交易阶段」和「业态阶段」揉在一起，无法从交易状态唯一反推。
   */
  public Order withTradeStatus(String newTradeStatus, String newLegacyStatus) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, newLegacyStatus, newTradeStatus, refundStatus, currencyCode, subtotalAmount,
        discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt,
        holdReason, holdAt, version);
  }

  /** 退款状态转换（返回新实例）。 */
  public Order withRefundStatus(String newRefundStatus) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, newRefundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt,
        version);
  }

  /** 金额重算后的新实例（{@code recalculate} 产出的五个金额列）。同上：不接收时间戳。 */
  public Order withAmounts(BigDecimal newSubtotal, BigDecimal newDiscount, BigDecimal newTax,
                           BigDecimal newTotal, BigDecimal newRefundable) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, refundStatus, currencyCode, newSubtotal, newDiscount, newTax,
        newTotal, paidAmount, newRefundable, completedAt, cancelledAt, holdReason, holdAt, version);
  }

  /** 挂单（返回新实例）。 */
  public Order held(String reason, LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, refundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, reason, at, version);
  }

  /** 解挂（返回新实例）。 */
  public Order unheld(LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, refundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, null, null, version);
  }

  /** 作废（返回新实例）：交易状态 VOIDED + 兼容列 VOIDED + cancelled_at。 */
  public Order voided(LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, "VOIDED", TradeStatus.VOIDED.name(), refundStatus, currencyCode, subtotalAmount,
        discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, at, holdReason,
        holdAt, version);
  }

  /**
   * 终结为指定交易终态（{@code CANCELLED} 用户取消 / {@code VOIDED} 审批作废），返回新实例。
   *
   * <p>取消与作废共用一条写路径：二者都写 {@code cancelled_at} 与兼容列，但交易状态不同
   * （报表按 VOIDED 独立统计），因此目标状态必须由调用方显式给出，不能在这里猜。
   */
  public Order terminated(TradeStatus target, LocalDateTime at) {
    if (target != TradeStatus.CANCELLED && target != TradeStatus.VOIDED) {
      throw new IllegalArgumentException("terminated 只接受 CANCELLED/VOIDED: " + target);
    }
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, target.legacyStatus(), target.name(), refundStatus, currencyCode, subtotalAmount,
        discountAmount, taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, at, holdReason,
        holdAt, version);
  }

  /** 版本自增后的新实例（CAS 成功后同步内存对象）。 */
  public Order withVersion(int newVersion) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, refundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt,
        newVersion);
  }

  /** 落库后回填主键的新实例（聚合不可变，插入由适配器返回带 id 的副本）。 */
  public Order withId(Long newId) {
    return new Order(newId, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, tradeStatus, refundStatus, currencyCode, subtotalAmount, discountAmount,
        taxAmount, totalAmount, paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt,
        version);
  }
}
