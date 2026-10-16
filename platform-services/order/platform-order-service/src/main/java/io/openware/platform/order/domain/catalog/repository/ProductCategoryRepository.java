package io.openware.platform.order.domain.catalog.repository;

import io.openware.platform.order.domain.catalog.model.ProductCategory;
import java.util.List;
import java.util.Optional;

/**
 * 商品分类仓储端口。
 *
 * <p>分类是门店内的名称字典：名称在门店内唯一（{@code uk_ord_product_category_store_name}），
 * 商品与目录项以名称冗余引用，因此改名要连带改写引用（由应用层在同一事务内编排，
 * 分别经 {@link ProductRepository#renameCategory} 与
 * {@link CatalogItemRepository#renameCategory}）。
 */
public interface ProductCategoryRepository {

  /** 按标识取分类；租户/门店归属由调用方核对并给出稳定错误码。 */
  Optional<ProductCategory> findById(Long id);

  /** 按门店 + 名称精确取分类（重名校验的口径）。 */
  Optional<ProductCategory> findByName(Long tenantId, Long storeId, String name);

  /** 按门店列出分类（按 {@code sort_order, name, id} 升序）；{@code status} 非空时只看该状态。 */
  List<ProductCategory> find(Long tenantId, Long storeId, String status);

  /** 新增分类，返回落库后的聚合与审计快照（含回填主键）。 */
  CatalogItemRepository.Persisted<ProductCategory> insert(ProductCategory category);

  /** 覆盖写入既有分类的字段（返回更新后的聚合与审计快照）。 */
  CatalogItemRepository.Persisted<ProductCategory> update(ProductCategory category);

  /** 删除分类（物理删除；「被商品引用禁止删除」由应用层先行判定）。 */
  void deleteById(Long id);
}
