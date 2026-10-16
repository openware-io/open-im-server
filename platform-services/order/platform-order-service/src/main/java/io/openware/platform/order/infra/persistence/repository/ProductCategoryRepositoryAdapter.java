package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository;
import io.openware.platform.order.domain.catalog.repository.ProductCategoryRepository;
import io.openware.platform.order.infra.persistence.mapper.ProductCategoryMapper;
import io.openware.platform.order.infra.persistence.po.ProductCategoryPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ProductCategoryRepository} 的持久化适配器。
 *
 * <p><b>审计列由本层负责</b>：{@code created_by / created_at / updated_by / updated_at} 是记账信息，
 * 不是业务事实（standards/10），领域模型没有它们。{@link #insert} 写 {@code now}，
 * {@link #update} 刷新 {@code updated_at} 并从库里取回不可变的 {@code created_*}。
 *
 * <p>{@code ProductCategoryPo} 上**没有** {@code @TableField(exist = false)} 字段
 * （逐字段核对过：DDL 的 {@code ord_product_category} 与 PO 一一对应），
 * 因此这里不需要像目录项/商品适配器那样刻意跳过展示字段——但仍不写非列字段，
 * 因为一旦将来给 PO 加展示字段，写进去就会在运行时炸。
 */
@Repository
public class ProductCategoryRepositoryAdapter implements ProductCategoryRepository {

  private final ProductCategoryMapper mapper;

  public ProductCategoryRepositoryAdapter(ProductCategoryMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<ProductCategory> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectById(id)).map(ProductCategoryRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<ProductCategory> findByName(Long tenantId, Long storeId, String name) {
    return mapper.selectList(Wrappers.<ProductCategoryPo>lambdaQuery()
            .eq(ProductCategoryPo::getTenantId, tenantId)
            .eq(ProductCategoryPo::getStoreId, storeId)
            .eq(ProductCategoryPo::getName, name)
            .last("LIMIT 1"))
        .stream().findFirst().map(ProductCategoryRepositoryAdapter::toDomain);
  }

  @Override
  public List<ProductCategory> find(Long tenantId, Long storeId, String status) {
    return mapper.selectList(Wrappers.<ProductCategoryPo>lambdaQuery()
            .eq(ProductCategoryPo::getTenantId, tenantId)
            .eq(ProductCategoryPo::getStoreId, storeId)
            .eq(status != null, ProductCategoryPo::getStatus, status)
            .orderByAsc(ProductCategoryPo::getSortOrder)
            .orderByAsc(ProductCategoryPo::getName)
            .orderByAsc(ProductCategoryPo::getId))
        .stream().map(ProductCategoryRepositoryAdapter::toDomain).toList();
  }

  @Override
  public CatalogItemRepository.Persisted<ProductCategory> insert(ProductCategory category) {
    ProductCategoryPo po = toPo(category);
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // created_by/updated_by 留空：DDL 是 NOT NULL DEFAULT 0（0 = 系统），与既有 doCreate 同口径。
    // 唯一键 uk_ord_product_category_store_name（门店内分类名唯一）冲突必须抛出，不吞。
    mapper.insert(po);
    return persisted(po);
  }

  @Override
  public CatalogItemRepository.Persisted<ProductCategory> update(ProductCategory category) {
    ProductCategoryPo existing = category.id() == null ? null : mapper.selectById(category.id());
    ProductCategoryPo po = toPo(category);
    if (existing != null) {
      po.setCreatedAt(existing.getCreatedAt());
      po.setCreatedBy(existing.getCreatedBy());
      po.setUpdatedBy(existing.getUpdatedBy());
    }
    po.setUpdatedAt(LocalDateTime.now());
    mapper.updateById(po);
    return persisted(po);
  }

  /** 业务聚合 + 审计快照：分开交给上层，领域模型不承载记账字段。 */
  private static CatalogItemRepository.Persisted<ProductCategory> persisted(ProductCategoryPo po) {
    return new CatalogItemRepository.Persisted<>(toDomain(po),
        new CatalogItemRepository.AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(),
            po.getUpdatedAt()));
  }

  @Override
  public void deleteById(Long id) {
    mapper.deleteById(id);
  }

  /** 领域模型 → 持久化对象（只写业务列；审计列由 insert/update 按落库口径补齐）。 */
  private static ProductCategoryPo toPo(ProductCategory category) {
    ProductCategoryPo po = new ProductCategoryPo();
    po.setId(category.id());
    po.setTenantId(category.tenantId());
    po.setStoreId(category.storeId());
    po.setName(category.name());
    po.setSortOrder(category.sortOrder());
    po.setStatus(category.status());
    return po;
  }

  private static ProductCategory toDomain(ProductCategoryPo po) {
    if (po == null) {
      return null;
    }
    return new ProductCategory(po.getId(), po.getTenantId(), po.getStoreId(), po.getName(), po.getSortOrder(),
        po.getStatus());
  }
}
