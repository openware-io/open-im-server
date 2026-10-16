package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.order.model.OrderItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细（加项）的对外视图（{@code /business/orders/{orderId}/items} 的响应体）。
 *
 * <p><b>字段集照搬原持久化对象 {@code OrderItemPo}（JSON 字段名一个不改）</b>：
 * 加项列表与确认/拒绝的响应早已是对外契约（见
 * {@code docs/contracts/openapi/platform-order-service.json} 的 {@code OrderItemPo} schema）。
 * 明细没有 {@code @TableField(exist = false)} 字段，因此本视图与列一一对应。
 *
 * <p><b>审计四列保留、但不假装有值</b>：{@code createdBy}/{@code createdAt}/{@code updatedBy}/
 * {@code updatedAt} 是记账信息、不进领域模型（standards/10 D2）。字段名保留（原实现直接序列化
 * 持久化对象），由视图装配时取 {@code null}——要回填真值需要在读路径带回记账快照
 * （{@link io.openware.platform.order.domain.order.repository.Persisted}），属于另一次有意识的契约变更。
 *
 * @param id                        明细标识
 * @param tenantId                  租户
 * @param storeId                   门店
 * @param businessType              业态
 * @param orderId                   所属订单
 * @param itemType                  明细类型
 * @param catalogItemId             关联目录项
 * @param resourceId                关联资源
 * @param nameSnapshot              名称快照
 * @param unitPrice                 单价（最小货币单位）
 * @param quantity                  数量
 * @param discountAmount            优惠金额
 * @param taxAmount                 税额
 * @param totalAmount               小计
 * @param currencyCode              币种快照
 * @param priceSnapshotJson         价格快照 JSON
 * @param status                    ACTIVE / PENDING_APPROVAL / REJECTED / …
 * @param source                    MERCHANT / CUSTOMER
 * @param inventoryStatus           库存状态
 * @param inventoryMaterialId       关联库存物料
 * @param inventoryRecoveryDecision 库存回收决定
 * @param inventoryRecoveryReason   库存回收原因
 * @param inventoryRecoveryDecidedBy 回收决定人
 * @param inventoryRecoveryDecidedAt 回收决定时间
 * @param createdBy                 记账列：创建人（本视图不读记账快照，恒为 {@code null}）
 * @param createdAt                 记账列：创建时间（同上）
 * @param updatedBy                 记账列：最后更新人（同上）
 * @param updatedAt                 记账列：最后更新时间（同上）
 */
public record OrderItemView(
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
    LocalDateTime inventoryRecoveryDecidedAt,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

    /** 由领域聚合装配（四个审计列为空，见类注释）。 */
    public static OrderItemView from(OrderItem item) {
        if (item == null) {
            return null;
        }
        return new OrderItemView(item.id(), item.tenantId(), item.storeId(), item.businessType(),
            item.orderId(), item.itemType(), item.catalogItemId(), item.resourceId(), item.nameSnapshot(),
            item.unitPrice(), item.quantity(), item.discountAmount(), item.taxAmount(), item.totalAmount(),
            item.currencyCode(), item.priceSnapshotJson(), item.status(), item.source(), item.inventoryStatus(),
            item.inventoryMaterialId(), item.inventoryRecoveryDecision(), item.inventoryRecoveryReason(),
            item.inventoryRecoveryDecidedBy(), item.inventoryRecoveryDecidedAt(), null, null, null, null);
    }
}
