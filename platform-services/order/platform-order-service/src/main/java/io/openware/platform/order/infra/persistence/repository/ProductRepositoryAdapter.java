package io.openware.platform.order.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.Persisted;
import io.openware.platform.order.domain.catalog.repository.ProductRepository;
import io.openware.platform.order.infra.persistence.mapper.ProductMapper;
import io.openware.platform.order.infra.persistence.po.ProductPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link ProductRepository} 的持久化适配器。
 *
 * <p><b>审计列由本层负责</b>：{@code created_by / created_at / updated_by / updated_at} 是记账信息，
 * 不是业务事实（standards/10），领域模型没有它们。{@link #insert} 写 {@code now}，
 * {@link #update} 刷新 {@code updated_at} 并从库里取回不可变的 {@code created_*}。
 *
 * <p><b>JSON 列的归属</b>：{@code ord_product.image_urls} 是 {@code json} 列，
 * PO 上用 {@code @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)}
 * + {@code @TableName(autoResultMap = true)} 声明；领域模型只承载 {@code List<String>}，
 * 序列化/反序列化只发生在 {@link #toPo} / {@link #toDomain}。
 *
 * <p><b>非列字段绝不进入写入</b>：{@code ProductPo} 上的
 * {@code serverResourceName} 标了 {@code @TableField(exist = false)}（DDL 里没有这一列），
 * 本适配器不设它——它由应用层在响应期按 {@code server_resource_id} 从资源域回填到视图。
 * 给这类字段生成列引用会让 MyBatis-Plus 在运行时抛
 * {@code can not find lambda cache for this property [...]}。
 *
 * <p><b>列集合只来自真实 DDL</b>（{@code V1__ord_order_baseline.sql} 的 {@code ord_product}）：
 * id / tenant_id / store_id / product_code / name / category / item_type / unit / sale_price /
 * material_id / server_resource_id / stock_controlled / catalog_item_id / status / sort_order /
 * description / image_urls / main_image_url / version + created_by / created_at / updated_by / updated_at。
 */
@Repository
public class ProductRepositoryAdapter implements ProductRepository {

  private final ProductMapper mapper;

  public ProductRepositoryAdapter(ProductMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Product> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(mapper.selectById(id)).map(ProductRepositoryAdapter::toDomain);
  }

  @Override
  public List<Product> find(ProductQuery query) {
    return mapper.selectList(Wrappers.<ProductPo>lambdaQuery()
            .eq(ProductPo::getTenantId, query.tenantId())
            .eq(query.storeId() != null, ProductPo::getStoreId, query.storeId())
            .eq(query.status() != null, ProductPo::getStatus, query.status())
            .eq(query.materialId() != null, ProductPo::getMaterialId, query.materialId())
            .eq(query.catalogItemId() != null, ProductPo::getCatalogItemId, query.catalogItemId())
            // 时间条件直接落在 created_at 列上，不用函数包裹（保持索引可用，口径与迁移前一致）。
            .ge(query.createdFrom() != null, ProductPo::getCreatedAt, query.createdFrom())
            .le(query.createdTo() != null, ProductPo::getCreatedAt, query.createdTo())
            .orderByAsc(ProductPo::getSortOrder)
            .orderByAsc(ProductPo::getId))
        .stream().map(ProductRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Persisted<Product> insert(Product product) {
    ProductPo po = toPo(product);
    LocalDateTime now = LocalDateTime.now();
    if (po.getVersion() == null) {
      po.setVersion(0);
    }
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // created_by/updated_by 留空：DDL 是 NOT NULL DEFAULT 0（0 = 系统），既有 syncCatalog 亦如此。
    // 唯一键（门店内编码 / 服务人员占用）冲突必须抛出，不吞。
    mapper.insert(po);
    return persisted(po);
  }

  @Override
  public Persisted<Product> update(Product product, int expectedVersion) {
    ProductPo existing = product.id() == null ? null : mapper.selectById(product.id());
    ProductPo po = toPo(product);
    // id 只做 WHERE 条件；version 交给 setSql 自增，避免实体 SET 覆盖成旧值。
    po.setId(null);
    po.setVersion(null);
    if (existing != null) {
      po.setCreatedAt(existing.getCreatedAt());
      po.setCreatedBy(existing.getCreatedBy());
      po.setUpdatedBy(existing.getUpdatedBy());
    }
    po.setUpdatedAt(LocalDateTime.now());
    // 实体更新（NOT_NULL 策略）+ 显式 CAS：null 字段不写列，与迁移前的 updateById 逐列一致。
    int affected = mapper.update(po, Wrappers.<ProductPo>lambdaUpdate()
        .eq(ProductPo::getId, product.id())
        .eq(ProductPo::getVersion, expectedVersion)
        .setSql("version = version + 1"));
    if (affected == 0) {
      throw new IllegalStateException("PRODUCT_VERSION_CONFLICT");
    }
    ProductPo written = mapper.selectById(product.id());
    return persisted(written != null ? written : po);
  }

  /** 业务聚合 + 审计快照：分开交给上层，领域模型不承载记账字段。 */
  private static Persisted<Product> persisted(ProductPo po) {
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  @Override
  public long countByCategory(Long tenantId, Long storeId, String category) {
    return mapper.selectCount(Wrappers.<ProductPo>lambdaQuery()
        .eq(ProductPo::getTenantId, tenantId)
        .eq(ProductPo::getStoreId, storeId)
        .eq(ProductPo::getCategory, category));
  }

  @Override
  public int renameCategory(Long tenantId, Long storeId, String oldCategory, String newCategory) {
    // 单条条件 UPDATE：改名是批量改写，逐行读改写会产生 N 次写与并发覆盖。
    return mapper.update(null, Wrappers.<ProductPo>lambdaUpdate()
        .eq(ProductPo::getTenantId, tenantId)
        .eq(ProductPo::getStoreId, storeId)
        .eq(ProductPo::getCategory, oldCategory)
        .set(ProductPo::getCategory, newCategory)
        .set(ProductPo::getUpdatedAt, LocalDateTime.now()));
  }

  @Override
  public Optional<Product> findServerResourceOccupant(Long tenantId, Long storeId, String itemType,
      Long serverResourceId, Long excludeProductId) {
    if (serverResourceId == null) {
      return Optional.empty();
    }
    return mapper.selectList(Wrappers.<ProductPo>lambdaQuery()
            .eq(ProductPo::getTenantId, tenantId)
            .eq(ProductPo::getStoreId, storeId)
            .eq(ProductPo::getItemType, itemType)
            .eq(ProductPo::getServerResourceId, serverResourceId)
            .ne(excludeProductId != null, ProductPo::getId, excludeProductId)
            .last("LIMIT 1"))
        .stream().findFirst().map(ProductRepositoryAdapter::toDomain);
  }

  /** 领域模型 → 持久化对象（只写业务列；{@code serverResourceName} 不是列，这里不设）。 */
  static ProductPo toPo(Product product) {
    ProductPo po = new ProductPo();
    po.setId(product.id());
    po.setTenantId(product.tenantId());
    po.setStoreId(product.storeId());
    po.setProductCode(product.productCode());
    po.setName(product.name());
    po.setCategory(product.category());
    po.setItemType(product.itemType());
    po.setUnit(product.unit());
    po.setSalePrice(product.salePrice());
    po.setMaterialId(product.materialId());
    po.setServerResourceId(product.serverResourceId());
    po.setStockControlled(product.stockControlled());
    po.setCatalogItemId(product.catalogItemId());
    po.setStatus(product.status());
    po.setSortOrder(product.sortOrder());
    po.setDescription(product.description());
    // List<String> → json 列：由 PO 上声明的 JacksonTypeHandler 序列化（见类注释）。
    po.setImageUrls(product.imagesOrEmpty());
    po.setMainImageUrl(product.mainImageUrl());
    po.setVersion(product.version());
    return po;
  }

  /** 持久化对象 → 领域模型（只取业务列；json 列由 JacksonTypeHandler 反序列化成 List）。 */
  static Product toDomain(ProductPo po) {
    if (po == null) {
      return null;
    }
    return new Product(po.getId(), po.getTenantId(), po.getStoreId(), po.getProductCode(), po.getName(),
        po.getCategory(), po.getItemType(), po.getUnit(), po.getSalePrice(), po.getMaterialId(),
        po.getServerResourceId(), po.getStockControlled(), po.getCatalogItemId(), po.getStatus(), po.getSortOrder(),
        po.getDescription(), po.getImageUrls(), po.getMainImageUrl(), po.getVersion());
  }
}
