package io.openware.platform.order.domain.catalog.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 点单目录项（领域模型）：B 端点单与 C 端自助加项共用的可售条目。
 *
 * <p><b>只有真实列，且不含审计字段</b>。审计四列（{@code created_by / created_at / updated_by /
 * updated_at}）是记账信息、不是业务事实（standards/10「聚合装配与持久化契约」），
 * 由适配器在写入时填充、在读取时丢弃——与 {@code WalletAccount} / {@code PointAccount} 同一约定。
 * 响应里仍然带这四个字段名（对外契约不变），值由视图在装配期从持久化对象取；
 * 领域模型不因为「视图要用」而承载它们。
 *
 * <p>持久化对象 {@code CatalogItemPo} 上的三个
 * {@code available} / {@code unavailableReason} / {@code availableQuantity} 标了
 * {@code @TableField(exist = false)}，DDL（{@code V1__ord_order_baseline.sql} 的 {@code ord_catalog_item}）
 * 里也没有这三列：它们由响应期计算（可点性看「关联商品 + 其关联物料」、可用量走库存批量读），
 * 因此**不在本模型里承载**——它们属于 {@code CatalogItemView}。
 * 把它们放进这里只会诱使写入路径生成列引用，而 MyBatis-Plus 对
 * {@code exist = false} 的字段生成列引用会在<b>运行时</b>抛
 * {@code can not find lambda cache for this property [...]}（{@code test-compile} 完全看不出来，
 * 钱包切片已经栽过一次）。
 *
 * <p><b>{@code unitPrice} 是 {@link BigDecimal}</b>：DDL 是
 * {@code unit_price decimal(20,6) NOT NULL COMMENT '单价（最小货币单位：分）'}，
 * 即「以最小货币单位计价的定点数」。这里不改类型——改成 long 分会丢掉既有的 6 位小数口径，
 * 改成 double 会引入二进制浮点误差。
 *
 * <p><b>{@code imageUrls} 是普通的 {@code List<String>}</b>：JSON 列与 {@code JacksonTypeHandler}
 * 是持久化细节，只允许出现在适配器里（见 {@code CatalogItemRepositoryAdapter}）。
 *
 * <p>不可变记录：字段变更一律返回新实例（{@code with*}），没有 setter。
 *
 * @param id              目录项标识；{@code null} 表示尚未落库
 * @param productId       关联商品（{@code ord_product.id}）；{@code null} = 纯服务/加项目录项
 * @param tenantId        租户
 * @param storeId         门店；{@code null} = 租户级通用
 * @param category        分类名（以名称冗余引用 {@code ord_product_category}，改名走同步改写）
 * @param itemType        PRODUCT / SERVICE / PACKAGE / ADD_ON
 * @param name            名称
 * @param unit            单位
 * @param unitPrice       单价（最小货币单位，定点）
 * @param description     描述
 * @param imageUrls       图片 URL 列表（落库为 JSON 数组文本，序列化在适配器）
 * @param mainImageUrl    主图 URL，必须属于 {@code imageUrls}
 * @param status          ACTIVE / INACTIVE
 * @param stockControlled 是否受库存控制（**只是查询缓存/兜底**，权威口径是关联商品 + 物料）
 * @param sortOrder       排序，越小越靠前
 */
public record CatalogItem(
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
    Integer sortOrder) {

  /** 可点状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 停用（软删）。 */
  public static final String STATUS_INACTIVE = "INACTIVE";

  /** 实物商品（默认类型）。 */
  public static final String ITEM_TYPE_PRODUCT = "PRODUCT";

  /** 服务：人员的服务，不占库存、单价取自目录项自身。 */
  public static final String ITEM_TYPE_SERVICE = "SERVICE";

  /** 是否服务类目录项（{@code item_type=SERVICE}，大小写与空白容错）。 */
  public boolean serviceItem() {
    return ITEM_TYPE_SERVICE.equalsIgnoreCase(itemType == null ? "" : itemType.trim());
  }

  /** 是否可点（仅看状态列；真正的可点性还要看关联商品与库存）。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 图片列表（为空视为空列表，调用方无需再判空）。 */
  public List<String> imagesOrEmpty() {
    return imageUrls == null ? List.of() : imageUrls;
  }

  /** 是否已落库（{@code id == null} 即尚未落库）。 */
  public boolean persisted() {
    return id != null;
  }

  /** 新建目录项（尚未落库；{@code status=ACTIVE}、{@code sortOrder=0}）。 */
  public static CatalogItem draft(Long tenantId, Long storeId, String category, String itemType, String name,
      String unit, BigDecimal unitPrice, String description, List<String> imageUrls, String mainImageUrl,
      Integer sortOrder) {
    return new CatalogItem(null, null, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        imageUrls == null ? List.of() : imageUrls, mainImageUrl, STATUS_ACTIVE, false,
        sortOrder == null ? 0 : sortOrder);
  }

  /** 图片与主图（返回新实例）。 */
  public CatalogItem withImages(List<String> urls, String main) {
    return new CatalogItem(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        urls == null ? List.of() : urls, main, status, stockControlled, sortOrder);
  }

  /** 落库后回填主键（返回新实例）。 */
  public CatalogItem withId(Long newId) {
    return new CatalogItem(newId, productId, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        imageUrls, mainImageUrl, status, stockControlled, sortOrder);
  }

  /**
   * 商品同步写入（{@code ProductApplicationService.syncCatalog} 的唯一出口）：返回新实例。
   *
   * <p>只覆盖商品侧权威的业务字段；审计列由适配器在 UPDATE 时自行刷新。
   */
  public CatalogItem syncedFromProduct(Long newProductId, Long newTenantId, Long newStoreId, String newCategory,
      String newItemType, String newName, String newUnit, BigDecimal newUnitPrice, String newDescription,
      String newStatus, boolean newStockControlled, Integer newSortOrder) {
    return new CatalogItem(id, newProductId, newTenantId, newStoreId, newCategory, newItemType, newName, newUnit,
        newUnitPrice, newDescription, imageUrls, mainImageUrl, newStatus, newStockControlled, newSortOrder);
  }

  /** 分类改名同步（返回新实例）。 */
  public CatalogItem withCategory(String newCategory) {
    return new CatalogItem(id, productId, tenantId, storeId, newCategory, itemType, name, unit, unitPrice, description,
        imageUrls, mainImageUrl, status, stockControlled, sortOrder);
  }

  /**
   * 库存控制缓存列（返回新实例）。
   *
   * <p>该列只是查询缓存/兜底，权威口径是「关联商品 + 其关联物料」；读写路径见
   * {@code CatalogController} 与 {@code OrderItemController}。
   */
  public CatalogItem withStockControlled(Boolean newStockControlled) {
    return new CatalogItem(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        imageUrls, mainImageUrl, status, newStockControlled, sortOrder);
  }

  /** 图片镜像（物料换图后回填到镜像该物料的目录项；返回新实例）。 */
  public CatalogItem withMirroredImages(List<String> urls, String main) {
    return new CatalogItem(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        urls == null ? List.of() : urls, main, status, stockControlled, sortOrder);
  }

  /** 停用（软删，返回新实例）。 */
  public CatalogItem disabled() {
    return new CatalogItem(id, productId, tenantId, storeId, category, itemType, name, unit, unitPrice, description,
        imageUrls, mainImageUrl, STATUS_INACTIVE, stockControlled, sortOrder);
  }
}
