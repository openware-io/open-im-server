package io.openware.platform.order.application.dto;

import io.openware.platform.order.domain.catalog.model.CatalogItem;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 点单目录项一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code CatalogItemPo}（JSON 字段名一个不改）</b>：
 * {@code GET /business/catalog/items} 的响应体早已是对外契约
 * （见 {@code docs/contracts/openapi/platform-order-service.json} 的 {@code CatalogItemPo} schema），
 * 换个名字同样是破坏性变更。收紧字段集属于另一次带版本号的变更，不在本次范围内。
 *
 * <p>其中三个字段**不是数据库列**（原 PO 上标 {@code @TableField(exist = false)}，
 * DDL 的 {@code ord_catalog_item} 里也没有）：
 * {@link #available}（当前是否可点）、{@link #unavailableReason}（不可点原因）、
 * {@link #availableQuantity}（受库存控制时的可用数量）。
 * 它们由响应期计算：可点性看「关联商品 + 其关联物料」，可用量走库存批量读 + TTL 缓存。
 * <b>它们绝不能进入任何列引用</b>——对 {@code exist = false} 的字段生成列引用会让 MyBatis-Plus
 * 在运行时抛 {@code can not find lambda cache for this property [...]}。
 * 放在视图里、而不是领域模型里，正是为了让这条约束在类型层面就成立：
 * 持有本类型的代码根本拿不到 Mapper。
 *
 * <p>{@code availableQuantity} 的类型是 {@link BigDecimal}：它与
 * {@code ord_inventory_stock.on_hand_qty}（{@code decimal(20,6)}）同口径，
 * 是**数量**而不是金额，也不落这张表。
 *
 * @param id                目录项标识
 * @param productId         关联商品
 * @param tenantId          租户
 * @param storeId           门店（{@code null} = 租户级通用）
 * @param category          分类名
 * @param itemType          PRODUCT / SERVICE / PACKAGE / ADD_ON
 * @param name              名称
 * @param unit              单位
 * @param unitPrice         单价（最小货币单位，定点）
 * @param description       描述
 * @param imageUrls         图片 URL 列表
 * @param mainImageUrl      主图 URL
 * @param status            ACTIVE / INACTIVE
 * @param stockControlled   是否受库存控制（读取期会被商品的权威口径回写）
 * @param sortOrder         排序
 * @param createdBy         创建人
 * @param createdAt         创建时间
 * @param updatedBy         更新人
 * @param updatedAt         更新时间
 * @param available         非列：当前是否可点（未上架/未关联物料/售罄时为 false）
 * @param unavailableReason 非列：不可点原因（售罄/未上架/未关联物料）
 * @param availableQuantity 非列：受库存控制时的可用库存数量；不控制库存为 {@code null} = 不限量
 */
public record CatalogItemView(
    Long id,
    Long productId,
    Long tenantId,
    Long storeId,
    String category,
    String itemType,
    String name,
    String unit,
    BigDecimal unitPrice,
    String description,
    List<String> imageUrls,
    String mainImageUrl,
    String status,
    Boolean stockControlled,
    Integer sortOrder,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt,
    Boolean available,
    String unavailableReason,
    BigDecimal availableQuantity) {

  /**
   * 由领域模型 + 审计快照构造。
   *
   * <p>审计四列（createdBy/createdAt/updatedBy/updatedAt）**不在领域模型里**
   * （它们是记账信息，不是业务事实，见 standards/10 与 {@code CatalogItem} 的说明），
   * 但响应的 JSON 契约要求这四个字段名继续存在，因此视图在装配期从端口返回的
   * {@link AuditSnapshot} 取它们的值——不依赖任何持久化类型。
   *
   * @param audit 该行落库后的审计快照；{@code null} 表示没有可读的审计值（此时四列为 null）
   */
  public static CatalogItemView from(CatalogItem item, AuditSnapshot audit) {
    if (item == null) {
      return null;
    }
    return new CatalogItemView(item.id(), item.productId(), item.tenantId(), item.storeId(), item.category(),
        item.itemType(), item.name(), item.unit(), item.unitPrice(), item.description(), item.imagesOrEmpty(),
        item.mainImageUrl(), item.status(), item.stockControlled(), item.sortOrder(),
        audit == null ? null : audit.createdBy(),
        audit == null ? null : audit.createdAt(),
        audit == null ? null : audit.updatedBy(),
        audit == null ? null : audit.updatedAt(), null, null, null);
  }

  /** 图片兜底后的新视图（返回新实例）。 */
  public CatalogItemView withImages(List<String> urls, String main) {
    return new CatalogItemView(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice,
        description, urls, main, status, stockControlled, sortOrder, createdBy, createdAt, updatedBy, updatedAt,
        available, unavailableReason, availableQuantity);
  }

  /** 可点性与库存标记（返回新实例）。 */
  public CatalogItemView withAvailability(Boolean newAvailable, String reason, Boolean newStockControlled,
      BigDecimal newAvailableQuantity) {
    return new CatalogItemView(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice,
        description, imageUrls, mainImageUrl, status, newStockControlled, sortOrder, createdBy, createdAt,
        updatedBy, updatedAt, newAvailable, reason, newAvailableQuantity);
  }
}
