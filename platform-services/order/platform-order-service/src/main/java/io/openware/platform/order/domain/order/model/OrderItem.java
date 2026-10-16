package io.openware.platform.order.domain.order.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细（加项）聚合（领域模型）。
 *
 * <p>{@code OrderItemPo} 的 24 个字段<b>全是真实列</b>（无 {@code @TableField(exist = false)}），
 * 因此本记录与列一一对应，不存在"非列字段被误写进 SQL"的隐患。
 *
 * <p>审计字段（createdBy / createdAt / updatedBy / updatedAt）不在此承载：由适配器填充。
 *
 * @param id                        明细标识
 * @param tenantId                  租户
 * @param storeId                   门店
 * @param businessType              业态
 * @param orderId                   所属订单
 * @param itemType                  明细类型
 * @param catalogItemId             关联目录项
 * @param resourceId                关联资源（服务人员/包厢等）
 * @param nameSnapshot              名称快照
 * @param unitPrice                 单价（最小货币单位，定点）
 * @param quantity                  数量
 * @param discountAmount            优惠金额
 * @param taxAmount                 税额
 * @param totalAmount               小计
 * @param currencyCode              币种快照；已结算明细锁定，改设置不改历史
 * @param priceSnapshotJson         价格快照 JSON
 * @param status                    状态：ACTIVE / PENDING_APPROVAL / REJECTED / …
 * @param source                    来源：MERCHANT（商户/服务员，直接生效）/ CUSTOMER（消费者，待确认）
 * @param inventoryStatus           库存扣减状态
 * @param inventoryMaterialId       关联库存物料
 * @param inventoryRecoveryDecision 库存回收决定
 * @param inventoryRecoveryReason   库存回收原因
 * @param inventoryRecoveryDecidedBy 回收决定人
 * @param inventoryRecoveryDecidedAt 回收决定时间
 */
public record OrderItem(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long orderId,
    String itemType,
    Long catalogItemId,
    Long resourceId,
    String nameSnapshot,
    BigDecimal unitPrice,
    BigDecimal quantity,
    BigDecimal discountAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String currencyCode,
    String priceSnapshotJson,
    String status,
    String source,
    String inventoryStatus,
    Long inventoryMaterialId,
    String inventoryRecoveryDecision,
    String inventoryRecoveryReason,
    Long inventoryRecoveryDecidedBy,
    LocalDateTime inventoryRecoveryDecidedAt) {

  /** 已生效：计入订单应付。待确认（PENDING_APPROVAL）与已拒绝（REJECTED）不计入。 */
  public static final String STATUS_ACTIVE = "ACTIVE";
  /** 待确认：消费者自助加项，需门店确认后才计入应付。 */
  public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
  /** 已拒绝。 */
  public static final String STATUS_REJECTED = "REJECTED";

  /** 商户/服务员来源：直接生效。 */
  public static final String SOURCE_MERCHANT = "MERCHANT";
  /** 消费者来源：待确认。 */
  public static final String SOURCE_CUSTOMER = "CUSTOMER";

  /** 是否已生效（计入应付的唯一口径）。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 是否待确认。 */
  public boolean pendingApproval() {
    return STATUS_PENDING_APPROVAL.equals(status);
  }

  /** 小计（空视为 0）。 */
  public BigDecimal total() {
    return totalAmount == null ? BigDecimal.ZERO : totalAmount;
  }

  /** 数量（空视为 0）。 */
  public BigDecimal qty() {
    return quantity == null ? BigDecimal.ZERO : quantity;
  }

  /** 审批通过（返回新实例）。 */
  public OrderItem approved() {
    return withStatus(STATUS_ACTIVE);
  }

  /** 审批拒绝（返回新实例）。 */
  public OrderItem rejected() {
    return withStatus(STATUS_REJECTED);
  }

  /** 状态转换（返回新实例）。 */
  public OrderItem withStatus(String newStatus) {
    return new OrderItem(id, tenantId, storeId, businessType, orderId, itemType, catalogItemId,
        resourceId, nameSnapshot, unitPrice, quantity, discountAmount, taxAmount, totalAmount,
        currencyCode, priceSnapshotJson, newStatus, source, inventoryStatus, inventoryMaterialId,
        inventoryRecoveryDecision, inventoryRecoveryReason, inventoryRecoveryDecidedBy,
        inventoryRecoveryDecidedAt);
  }

  /** 落库后回填主键的新实例（聚合不可变，插入由适配器返回带 id 的副本）。 */
  public OrderItem withId(Long newId) {
    return new OrderItem(newId, tenantId, storeId, businessType, orderId, itemType, catalogItemId,
        resourceId, nameSnapshot, unitPrice, quantity, discountAmount, taxAmount, totalAmount,
        currencyCode, priceSnapshotJson, status, source, inventoryStatus, inventoryMaterialId,
        inventoryRecoveryDecision, inventoryRecoveryReason, inventoryRecoveryDecidedBy,
        inventoryRecoveryDecidedAt);
  }

  /** 库存状态转换（返回新实例）：加项落库后扣减成功 → {@code CONSUMED}。 */
  public OrderItem withInventoryStatus(String newInventoryStatus) {
    return new OrderItem(id, tenantId, storeId, businessType, orderId, itemType, catalogItemId,
        resourceId, nameSnapshot, unitPrice, quantity, discountAmount, taxAmount, totalAmount,
        currencyCode, priceSnapshotJson, status, source, newInventoryStatus, inventoryMaterialId,
        inventoryRecoveryDecision, inventoryRecoveryReason, inventoryRecoveryDecidedBy,
        inventoryRecoveryDecidedAt);
  }

  /**
   * 记录库存回收决定（返回新实例）：作废订单的库存回补处理结果与决定人/决定时刻。
   *
   * @param newInventoryStatus 回收后的库存状态（{@code REVERSED} / {@code NOT_RECOVERED}）
   * @param decision           回收决定（{@code RECOVERED} / {@code NOT_RECOVERED}）
   * @param reason             决定原因（运营填写）
   * @param decidedBy          决定人（无上下文时为 {@code null}）
   * @param decidedAt          决定时刻
   */
  public OrderItem withInventoryRecovery(String newInventoryStatus, String decision, String reason,
                                         Long decidedBy, LocalDateTime decidedAt) {
    return new OrderItem(id, tenantId, storeId, businessType, orderId, itemType, catalogItemId,
        resourceId, nameSnapshot, unitPrice, quantity, discountAmount, taxAmount, totalAmount,
        currencyCode, priceSnapshotJson, status, source, newInventoryStatus, inventoryMaterialId,
        decision, reason, decidedBy, decidedAt);
  }

  /**
   * 刷新计价快照列（返回新实例）：房费明细在开台/定时刷新/结台三条路径上按同一行更新金额与快照，
   * 其余列（类型、状态、来源、库存…）原样保留。
   *
   * @param newNameSnapshot      明细名快照（随计费口径变化：含服务人员与否）
   * @param newUnitPrice         单价（最小货币单位）
   * @param newQuantity          数量/计费块数
   * @param newTotalAmount       小计
   * @param newCurrencyCode      币种快照
   * @param newPriceSnapshotJson 计价方案快照 JSON
   */
  public OrderItem withPricing(String newNameSnapshot, BigDecimal newUnitPrice, BigDecimal newQuantity,
                               BigDecimal newTotalAmount, String newCurrencyCode, String newPriceSnapshotJson) {
    return new OrderItem(id, tenantId, storeId, businessType, orderId, itemType, catalogItemId,
        resourceId, newNameSnapshot, newUnitPrice, newQuantity, discountAmount, taxAmount, newTotalAmount,
        newCurrencyCode, newPriceSnapshotJson, status, source, inventoryStatus, inventoryMaterialId,
        inventoryRecoveryDecision, inventoryRecoveryReason, inventoryRecoveryDecidedBy,
        inventoryRecoveryDecidedAt);
  }
}
