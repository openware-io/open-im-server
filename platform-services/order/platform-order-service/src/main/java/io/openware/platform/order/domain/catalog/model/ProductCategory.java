package io.openware.platform.order.domain.catalog.model;

/**
 * 商品分类（领域模型）：商品管理页的分类字典，按门店维护。
 *
 * <p><b>只有真实列，且不含审计字段</b>。审计四列（{@code created_by / created_at / updated_by /
 * updated_at}）属于记账信息而非业务事实（standards/10「聚合装配与持久化契约」），
 * 由适配器在写入时填充、读取时丢弃——与 {@code WalletAccount} / {@code PointAccount} 同一约定。
 * 响应里仍然带这四个字段名（对外契约不变），值由视图在装配期从持久化对象取。
 *
 * <p>此外，持久化对象 {@code ProductCategoryPo} 与 DDL 的 {@code ord_product_category}
 * 逐列一一对应，没有 {@code @TableField(exist = false)} 的展示字段，
 * 因此本模型不存在「不是列」的成员。
 *
 * <p>商品与目录项以**分类名称**冗余引用（不引入外键），所以改名走「同步改名」：
 * 同一事务内把引用旧名的商品与目录项一起改掉（由应用层编排，本模型只表达事实）。
 *
 * @param id        分类标识；{@code null} 表示尚未落库
 * @param tenantId  租户
 * @param storeId   门店
 * @param name      分类名称（门店内唯一，唯一键 {@code uk_ord_product_category_store_name}）
 * @param sortOrder 排序，越小越靠前
 * @param status    ACTIVE / DISABLED
 */
public record ProductCategory(
    Long id,
    Long tenantId,
    Long storeId,
    String name,
    Integer sortOrder,
    String status) {

  /** 生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 已停用。 */
  public static final String STATUS_DISABLED = "DISABLED";

  /** 分类名称长度上限（DDL {@code name varchar(64)}）。 */
  public static final int MAX_NAME_LENGTH = 64;

  /** 是否已落库。 */
  public boolean persisted() {
    return id != null;
  }

  /** 新建分类（{@code status=ACTIVE}）。 */
  public static ProductCategory draft(Long tenantId, Long storeId, String name, Integer sortOrder) {
    return new ProductCategory(null, tenantId, storeId, name, sortOrder == null ? 0 : sortOrder, STATUS_ACTIVE);
  }

  /** 落库后回填主键（返回新实例）。 */
  public ProductCategory withId(Long newId) {
    return new ProductCategory(newId, tenantId, storeId, name, sortOrder, status);
  }

  /**
   * 编辑写入（返回新实例）：改名 / 排序 / 停用。
   *
   * <p>调用方先把「本次不改」解析成既有值（部分更新语义在应用层）。
   */
  public ProductCategory edited(String newName, Integer newSortOrder, String newStatus) {
    return new ProductCategory(id, tenantId, storeId, newName, newSortOrder, newStatus);
  }
}
