package io.openware.platform.order.domain.order.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 订单聚合（领域模型）。
 *
 * <p><b>只承载 {@code ord_order} 的真实列</b>，共 25 个。原持久化对象 {@code OrderPo} 上另有 13 个
 * {@code @TableField(exist = false)} 字段（sessionId / roomResourceId / roomName / roomCode / sessionStatus /
 * partySize / serverId / serverName / roomElapsedSeconds / roomEstimatedFee / liveTotalAmount /
 * sessionOpenedAt / sessionClosedAt）——它们是**从 {@code ord_ktv_session} 联表读出的投影**，
 * 不是订单自身的业务事实。它们住在 {@code application.dto.OrderView} 里，<b>不进领域模型</b>：
 * 领域模型一旦携带它们，任何整聚合写入都会试图为不存在的列生成引用，运行时抛
 * {@code can not find lambda cache for this property}，而编译期完全看不出来。
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
 * @param status           状态（见 {@link OrderStatus}）
 * @param currencyCode     币种快照
 * @param subtotalAmount   原价合计
 * @param discountAmount   优惠合计
 * @param taxAmount        税额
 * @param totalAmount      应付合计
 * @param paidAmount       已收金额
 * @param refundableAmount 可退金额
 * @param completedAt      完成时间
 * @param cancelledAt      作废/取消时间
 * @param holdReason       挂单原因（挂单是 SERVING 内的标记，不改商业状态）
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

  /** 终态集合：处于这些状态的订单不可再取消/作废。 */
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("COMPLETED", "VOIDED", "CANCELLED");

  /** 是否为终态。 */
  public boolean terminal() {
    return status != null && TERMINAL_STATUSES.contains(status);
  }

  /** 是否已作废。 */
  public boolean voided() {
    return "VOIDED".equals(status);
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

  /** 状态转换（返回新实例）。不接收时间戳：审计列不进领域模型，由适配器的语句自己写 {@code updated_at}。 */
  public Order withStatus(String newStatus) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, newStatus, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt, version);
  }

  /** 金额重算后的新实例（{@code recalculate} 产出的五个金额列）。同上：不接收时间戳。 */
  public Order withAmounts(BigDecimal newSubtotal, BigDecimal newDiscount, BigDecimal newTax,
                           BigDecimal newTotal, BigDecimal newRefundable) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, currencyCode, newSubtotal, newDiscount, newTax, newTotal, paidAmount,
        newRefundable, completedAt, cancelledAt, holdReason, holdAt, version);
  }

  /** 挂单（返回新实例）。 */
  public Order held(String reason, LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, cancelledAt, reason, at, version);
  }

  /** 解挂（返回新实例）。 */
  public Order unheld(LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, cancelledAt, null, null, version);
  }

  /** 作废（返回新实例）。 */
  public Order voided(LocalDateTime at) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, "VOIDED", currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, at, holdReason, holdAt, version);
  }

  /** 版本自增后的新实例（CAS 成功后同步内存对象）。 */
  public Order withVersion(int newVersion) {
    return new Order(id, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt, newVersion);
  }

  /** 落库后回填主键的新实例（聚合不可变，插入由适配器返回带 id 的副本）。 */
  public Order withId(Long newId) {
    return new Order(newId, tenantId, organizationId, storeId, orderNo, idempotencyKey, businessType,
        customerId, status, currencyCode, subtotalAmount, discountAmount, taxAmount, totalAmount,
        paidAmount, refundableAmount, completedAt, cancelledAt, holdReason, holdAt, version);
  }
}
