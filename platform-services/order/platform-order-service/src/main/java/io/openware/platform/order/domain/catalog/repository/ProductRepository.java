package io.openware.platform.order.domain.catalog.repository;

import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 商品仓储端口。
 *
 * <p>不暴露 MyBatis {@code Wrapper} / PO / Mapper：查询条件以业务语义命名（见 {@link ProductQuery}）。
 *
 * <p><b>列集合只来自真实 DDL</b>：见 {@link Product} 的说明——
 * {@code serverResourceName} 不是列（PO 上标了 {@code @TableField(exist = false)}），
 * 由应用层在响应期按 {@code server_resource_id} 从资源域回填。
 */
public interface ProductRepository {

  /** 按标识取商品；租户/门店归属由调用方核对并给出稳定错误码。 */
  Optional<Product> findById(Long id);

  /**
   * 按条件列出商品。
   *
   * <p>结果按 {@code sort_order, id} 升序；时间条件落在 {@code created_at} 列上、不用函数包裹，
   * 保持索引可用（原实现口径不变）。
   */
  List<Product> find(ProductQuery query);

  /**
   * 新增商品，返回落库后的聚合（含回填主键与 {@code version=0}）。
   *
   * <p>门店内商品编码唯一（{@code uk_ord_product_store_code}）与服务人员占用唯一
   * （{@code uk_ord_product_server_resource}）均由数据库兜底，撞键必须抛出。
   */
  Persisted<Product> insert(Product product);

  /**
   * 覆盖写入既有商品的字段（返回更新后的聚合与审计快照）。
   *
   * <p><b>乐观锁</b>：更新是单条 {@code WHERE id = ? AND version = ?} 的 CAS，
   * {@code version = version + 1} 写在 SQL 里；受影响 0 行表示并发冲突，适配器抛
   * {@code PRODUCT_VERSION_CONFLICT}，调用方必须拒绝而不是静默覆盖。
   *
   * <p>{@code null} 字段不写列（与既有 {@code updateById} 的 {@code NOT_NULL} 策略一致）。
   * 这与「切类型时清空 {@code server_resource_id}」的意图存在差异：既有实现在真实数据库上同样
   * 清不掉这一列（既有单测用 mock mapper，观察不到）。本端口保持既有行为，不顺手改语义。
   */
  Persisted<Product> update(Product product, int expectedVersion);

  /** 该门店下引用给定分类名的商品条数（「被商品引用的分类禁止删除」的判定）。 */
  long countByCategory(Long tenantId, Long storeId, String category);

  /** 分类改名同步：把该门店下引用 {@code oldCategory} 的商品改成 {@code newCategory}，返回受影响行数。 */
  int renameCategory(Long tenantId, Long storeId, String oldCategory, String newCategory);

  /**
   * 服务人员占用查询：该门店下 {@code item_type=SERVICE} 且关联了给定服务人员的商品
   * （用于给出可读的「该服务人员已关联服务商品「X」」提示）。
   *
   * @param excludeProductId 编辑时传当前商品 id（排除自己）；创建时传 {@code null}
   */
  Optional<Product> findServerResourceOccupant(Long tenantId, Long storeId, String itemType, Long serverResourceId,
      Long excludeProductId);

  /**
   * 商品查询条件（业务语义的组合，不是查询语法）。
   *
   * @param tenantId      租户（必填）
   * @param storeId       门店；非空时精确匹配
   * @param status        状态；非空时精确匹配
   * @param materialId    关联物料；非空时精确匹配（物料图片镜像口径）
   * @param catalogItemId 回指的目录项；非空时精确匹配（按目录项反查商品的加项口径）
   * @param createdFrom   创建时间下界（含）；{@code null} = 不筛
   * @param createdTo     创建时间上界（含）；{@code null} = 不筛
   */
  record ProductQuery(
      Long tenantId,
      Long storeId,
      String status,
      Long materialId,
      Long catalogItemId,
      LocalDateTime createdFrom,
      LocalDateTime createdTo) {

    /** 商品列表口径：本门店 + 可选状态 + 可选创建时间闭区间。 */
    public static ProductQuery inStore(Long tenantId, Long storeId, String status, LocalDateTime createdFrom,
        LocalDateTime createdTo) {
      return new ProductQuery(tenantId, storeId, status, null, null, createdFrom, createdTo);
    }

    /** 物料图片镜像口径：该门店下关联了给定物料的商品。 */
    public static ProductQuery linkedToMaterial(Long tenantId, Long storeId, Long materialId) {
      return new ProductQuery(tenantId, storeId, null, materialId, null, null, null);
    }

    /**
     * 加项口径：按目录项反查回指它的商品（{@code ord_product.catalog_item_id}）。
     *
     * <p>{@code storeId} 允许为空（订单门店缺失时退化为租户内查找，与既有实现一致）。
     */
    public static ProductQuery linkedToCatalogItem(Long tenantId, Long storeId, Long catalogItemId) {
      return new ProductQuery(tenantId, storeId, null, null, catalogItemId, null, null);
    }
  }
}
