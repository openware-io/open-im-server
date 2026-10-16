package io.openware.platform.order.domain.catalog.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 可售商品（领域模型）：实物商品与服务。
 *
 * <p><b>只有真实列，且不含审计字段</b>。审计四列（{@code created_by / created_at / updated_by /
 * updated_at}）是记账信息、不是业务事实（standards/10「聚合装配与持久化契约」），
 * 由适配器在写入时填充、在读取时丢弃——与 {@code WalletAccount} / {@code PointAccount} 同一约定。
 * 响应里仍然带这四个字段名（对外契约不变），值由视图在装配期从持久化对象取。
 *
 * <p>持久化对象 {@code ProductPo} 上的 {@code serverResourceName} 标了
 * {@code @TableField(exist = false)}（DDL 的 {@code ord_product} 里没有这一列），由
 * {@code ProductApplicationService.list} 按 {@code server_resource_id} 从资源域批量回填、
 * 资源服务不可达时降级为 null。它属于响应视图（{@code ProductView}），**不在本模型里承载**：
 * 一旦进模型，写入路径就可能生成列引用，而 MyBatis-Plus 对 {@code exist = false} 字段生成列引用
 * 会在运行时抛 {@code can not find lambda cache for this property [...]}。
 *
 * <p><b>{@code salePrice} 保持 {@link BigDecimal}</b>：DDL 是
 * {@code sale_price decimal(20,6) NOT NULL COMMENT '销售单价（分）'}。列注释里的「分」是**最小货币单位**
 * 的口径（与 {@code ord_catalog_item.unit_price}、{@code ord_inventory_material.purchase_price} 一致），
 * 不是要求把它压成整数：工程里价格一律以 {@code decimal(20,6)} 承载定点数，
 * 改成 long 分会静默丢掉既有小数位，改成 double 会引入二进制浮点误差。
 *
 * <p><b>{@code imageUrls} 是普通的 {@code List<String>}</b>：JSON 列与 {@code JacksonTypeHandler}
 * 只允许出现在适配器里。
 *
 * <p>不可变记录：字段变更一律返回新实例（{@code with*}），没有 setter。
 *
 * @param id                 商品标识；{@code null} 表示尚未落库
 * @param tenantId           租户
 * @param storeId            门店
 * @param productCode        门店内商品编码
 * @param name               名称
 * @param category           分类名（以名称冗余引用 {@code ord_product_category}）
 * @param itemType           PRODUCT（实物，默认）/ SERVICE（服务）
 * @param unit               销售单位
 * @param salePrice          销售单价（最小货币单位，定点）
 * @param materialId         关联库存物料；服务恒为 {@code null}
 * @param serverResourceId   服务型商品关联的服务人员（{@code res_resource.id}）；实物恒为 {@code null}
 * @param stockControlled    是否扣库存（服务恒为 false）
 * @param catalogItemId      回指的目录项（{@code ord_catalog_item.id}）
 * @param status             DRAFT / ON_SHELF / OFF_SHELF
 * @param sortOrder          排序
 * @param description        描述
 * @param imageUrls          图片 URL 列表（落库为 JSON 数组文本，序列化在适配器）
 * @param mainImageUrl       主图 URL，必须属于 {@code imageUrls}
 * @param version            乐观锁版本号列（本域写入路径尚不带条件，见仓储端口说明）
 */
public record Product(
    Long id,
    Long tenantId,
    Long storeId,
    String productCode,
    String name,
    String category,
    String itemType,
    String unit,
    BigDecimal salePrice,
    Long materialId,
    Long serverResourceId,
    Boolean stockControlled,
    Long catalogItemId,
    String status,
    Integer sortOrder,
    String description,
    List<String> imageUrls,
    String mainImageUrl,
    Integer version) {

  /** 实物商品（默认）。 */
  public static final String ITEM_TYPE_PRODUCT = "PRODUCT";

  /** 服务（人员的服务）。 */
  public static final String ITEM_TYPE_SERVICE = "SERVICE";

  /** 草稿：尚未上架。 */
  public static final String STATUS_DRAFT = "DRAFT";

  /** 已上架：进入点单目录。 */
  public static final String STATUS_ON_SHELF = "ON_SHELF";

  /** 已下架。 */
  public static final String STATUS_OFF_SHELF = "OFF_SHELF";

  /**
   * 商品类型的权威取值（兼容历史行 {@code item_type} 为空：一律按 PRODUCT 处理）。
   *
   * <p>原实现是 {@code ProductApplicationService#effectiveItemType(ProductPo)}；迁移后仍由应用层
   * 调用同一个口径，只是参数换成领域模型。
   */
  public String effectiveItemType() {
    return ITEM_TYPE_SERVICE.equalsIgnoreCase(itemType == null ? "" : itemType.trim())
        ? ITEM_TYPE_SERVICE
        : ITEM_TYPE_PRODUCT;
  }

  /** 是否服务型商品。 */
  public boolean serviceItem() {
    return ITEM_TYPE_SERVICE.equals(effectiveItemType());
  }

  /** 是否已落库。 */
  public boolean persisted() {
    return id != null;
  }

  /** 图片列表（为空视为空列表）。 */
  public List<String> imagesOrEmpty() {
    return imageUrls == null ? List.of() : imageUrls;
  }

  /** 新建商品（{@code status=DRAFT}、{@code version=0}）。 */
  public static Product draft(Long tenantId, Long storeId, String productCode, String name, String category,
      String itemType, String unit, BigDecimal salePrice, Long materialId, Long serverResourceId,
      boolean stockControlled, Integer sortOrder, String description, List<String> imageUrls, String mainImageUrl) {
    return new Product(null, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, null, STATUS_DRAFT, sortOrder == null ? 0 : sortOrder, description,
        imageUrls == null ? List.of() : imageUrls, mainImageUrl, 0);
  }

  /** 落库后回填主键（返回新实例）。 */
  public Product withId(Long newId) {
    return new Product(newId, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, catalogItemId, status, sortOrder, description, imageUrls, mainImageUrl,
        version);
  }

  /** 图片与主图（返回新实例）。 */
  public Product withImages(List<String> urls, String main) {
    return new Product(id, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, catalogItemId, status, sortOrder, description,
        urls == null ? List.of() : urls, main, version);
  }

  /**
   * 编辑写入（返回新实例）。
   *
   * <p>字段集合与原 {@code doUpdate} 逐项对应：名称/分类/单位/售价/类型/服务人员/库存开关/物料/描述。
   * 调用方负责先把「本次不改」解析成既有值（部分更新语义在应用层）。
   * {@code serverResourceId} 与 {@code materialId} 允许写 null（切类型时清空），
   * 适配器据此决定这一列是否参与 UPDATE（见 {@code ProductRepositoryAdapter} 的说明）。
   */
  public Product edited(String newName, String newCategory, String newUnit, BigDecimal newSalePrice,
      String newItemType, Long newServerResourceId, Boolean newStockControlled, Long newMaterialId,
      String newDescription, List<String> newImageUrls, String newMainImageUrl) {
    return new Product(id, tenantId, storeId, productCode, newName, newCategory, newItemType, newUnit, newSalePrice,
        newMaterialId, newServerResourceId, newStockControlled, catalogItemId, status, sortOrder, newDescription,
        newImageUrls == null ? List.of() : newImageUrls, newMainImageUrl, version);
  }

  /** 目录项回指回填（商品首次同步出目录项后写回 {@code catalog_item_id}，返回新实例）。 */
  public Product withCatalogItemId(Long newCatalogItemId) {
    return new Product(id, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, newCatalogItemId, status, sortOrder, description, imageUrls, mainImageUrl,
        version);
  }

  /** 上下架（返回新实例）。 */
  public Product withStatus(String newStatus) {
    return new Product(id, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, catalogItemId, newStatus, sortOrder, description, imageUrls, mainImageUrl,
        version);
  }
}
