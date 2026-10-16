package io.openware.platform.order.domain.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存物料档案（领域模型）。
 *
 * <p>此前应用层直接用 MyBatis 持久化对象 {@code InventoryMaterialPo} 承载该概念，使物料这一业务对象与
 * 表映射、MyBatis 注解、{@code LambdaQueryWrapper} 查询构造耦合在一起。本类型只表达业务事实。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_inventory_material}）：
 * {@code id / tenant_id / store_id / material_code / name / category / unit / description / safety_stock /
 * purchase_price / currency_code / image_urls / main_image_url / status / version / created_by / created_at /
 * updated_by / updated_at}。持久化对象上另有两个 {@code @TableField(exist = false)} 字段
 * {@code onHandQty} / {@code reservedQty}——DDL 里**没有**这两列，它们是库存余额
 * （{@code ord_inventory_stock}）的展示值，由列表读路径按物料 id 批量回填到响应视图
 * （{@code InventoryMaterialView}）。它们**不在本模型里承载**：一旦进模型，写入路径就可能为它们生成
 * 列引用，而 MyBatis-Plus 对 {@code exist = false} 字段生成列引用会在**运行时**抛
 * {@code can not find lambda cache for this property [...]}（{@code test-compile} 完全看不出来，
 * 钱包切片已经栽过一次）。
 *
 * <p><b>审计列不在本模型里</b>（standards/10「聚合装配与持久化契约」）：{@code created_by / updated_by /
 * updated_at} 是记账信息，由适配器在写入时填充、在读取时丢弃，与 {@code Product} / {@code CatalogItem} /
 * {@code WalletAccount} 同一约定。**{@code createdAt} 例外并刻意保留**：它是 {@code NOT NULL} 的真实列，
 * 是列表时间区间筛选（{@code ord_inventory_material.created_at}）的落点，也一直是响应字段；
 * 删掉它会改成契约（同 {@code PointLedgerEntry.createdAt} 的处理）。
 *
 * <p><b>{@code purchasePrice} 保持 {@link BigDecimal}</b>：DDL 是
 * {@code purchase_price decimal(20,6) NULL COMMENT '采购价（最小货币单位：分，每计量单位）'}。
 * 注释里的「分」是最小货币单位口径（与 {@code ord_product.sale_price}、
 * {@code ord_catalog_item.unit_price} 一致），不是要求压成整数：改成 long 分会静默丢掉既有的 6 位小数，
 * 改成 double 会引入二进制浮点误差。{@code null} = 未维护，与「0 分」区分（0 = 清空，落库归一为 NULL）。
 *
 * <p><b>{@code imageUrls} 是普通的 {@code List<String>}</b>：{@code image_urls} 是 {@code json} 列，
 * JSON 序列化与 {@code JacksonTypeHandler} 只允许出现在适配器里，应用层既不看见 JSON 也不构造它。
 *
 * <p>不可变记录：字段变更一律返回新实例（{@code edited} 等），没有 setter。
 *
 * @param id            物料标识；{@code null} 表示尚未落库
 * @param tenantId      租户
 * @param storeId       门店
 * @param materialCode  门店内物料编码（创建后不可改）
 * @param name          名称
 * @param category      分类
 * @param unit          计量单位
 * @param description   描述（≤255）
 * @param safetyStock   安全库存（最小计量单位，定点）
 * @param purchasePrice 采购价（最小货币单位/计量单位）；{@code null} = 未维护
 * @param currencyCode  采购价币种快照
 * @param imageUrls     图片 URL 列表（落库为 JSON 数组文本，序列化在适配器）；{@code null} = 无图
 * @param mainImageUrl  主图 URL，必须属于 {@code imageUrls}
 * @param status        ACTIVE / INACTIVE
 * @param version       乐观锁版本号列（库存余额的并发保护在 {@code ord_inventory_stock}，不在此列）
 * @param createdAt     创建时间（{@code NOT NULL} 真实列，见类注释）
 */
public record InventoryMaterial(
    Long id,
    Long tenantId,
    Long storeId,
    String materialCode,
    String name,
    String category,
    String unit,
    String description,
    BigDecimal safetyStock,
    BigDecimal purchasePrice,
    String currencyCode,
    List<String> imageUrls,
    String mainImageUrl,
    String status,
    Integer version,
    LocalDateTime createdAt) {

  /** 启用（可被商品关联、可出入库）。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 停用。 */
  public static final String STATUS_INACTIVE = "INACTIVE";

  /** 是否启用（大小写与空白容错，历史行 {@code status} 为空按停用处理）。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status == null ? "" : status.trim());
  }

  /** 是否已落库。 */
  public boolean persisted() {
    return id != null;
  }

  /** 图片列表（为空视为空列表，调用方无需再判空）。 */
  public List<String> imagesOrEmpty() {
    return imageUrls == null ? List.of() : imageUrls;
  }

  /**
   * 新建物料（尚未落库）。
   *
   * <p>只承载调用方表达的字段：{@code status=ACTIVE}、{@code version=0} 是落库口径，
   * {@code createdAt} 由适配器在写入时填充（审计列不进模型，见类注释）。
   */
  public static InventoryMaterial draft(Long tenantId, Long storeId, String materialCode, String name, String category,
      String unit, String description, BigDecimal safetyStock, BigDecimal purchasePrice, String currencyCode,
      List<String> imageUrls, String mainImageUrl) {
    return new InventoryMaterial(null, tenantId, storeId, materialCode, name, category, unit, description,
        safetyStock, purchasePrice, currencyCode, imageUrls, mainImageUrl, STATUS_ACTIVE, 0, null);
  }

  /** 落库后回填主键与落库时间（返回新实例）。 */
  public InventoryMaterial withIdAndCreatedAt(Long newId, LocalDateTime newCreatedAt) {
    return new InventoryMaterial(newId, tenantId, storeId, materialCode, name, category, unit, description,
        safetyStock, purchasePrice, currencyCode, imageUrls, mainImageUrl, status, version, newCreatedAt);
  }

  /**
   * 编辑写入（返回新实例）。
   *
   * <p>字段集合与迁移前 {@code updateMaterial} 可改的列逐项对应：名称/分类/单位/描述/安全库存/采购价/
   * 币种快照/图片/主图。调用方负责先把「本次不改」解析成既有值（部分更新语义在应用层），
   * 因此适配器可以把这些列**整组显式写出**——这正是「0 = 清空采购价」「清空描述」能在库里落成 NULL
   * 的原因（{@code updateById} 的 {@code NOT_NULL} 策略会把 null 整列跳过，表现为假成功）。
   *
   * <p>{@code materialCode} 不可改：编码是门店内的业务主键（{@code uk_ord_inventory_material_store_code}）。
   */
  public InventoryMaterial edited(String newName, String newCategory, String newUnit, String newDescription,
      BigDecimal newSafetyStock, BigDecimal newPurchasePrice, String newCurrencyCode, List<String> newImageUrls,
      String newMainImageUrl) {
    return new InventoryMaterial(id, tenantId, storeId, materialCode, newName, newCategory, newUnit, newDescription,
        newSafetyStock, newPurchasePrice, newCurrencyCode, newImageUrls, newMainImageUrl, status, version, createdAt);
  }
}
