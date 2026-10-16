package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.catalog.model.Product;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code ProductPo}（JSON 字段名一个不改）</b>：
 * {@code GET /admin/products}、{@code POST /admin/products}、{@code POST /admin/products/{id}/on-shelf}
 * 等端点的响应体早已是对外契约（见 {@code docs/contracts/openapi/platform-order-service.json}
 * 的 {@code ProductPo} schema）。
 *
 * <p>其中 {@link #serverResourceName} **不是数据库列**（原 PO 上标
 * {@code @TableField(exist = false)}，DDL 的 {@code ord_product} 里也没有这一列）：
 * 服务型商品的「服务人员名称」由应用层按 {@code server_resource_id} 从资源域批量回填，
 * 资源服务不可达时降级为 {@code null}（绝不因为跨域读失败而阻断商品列表）。
 * 它绝不能进入任何列引用——对 {@code exist = false} 的字段生成列引用会让 MyBatis-Plus
 * 在运行时抛 {@code can not find lambda cache for this property [...]}。
 *
 * @param id                 商品标识
 * @param tenantId           租户
 * @param storeId            门店
 * @param productCode        门店内商品编码
 * @param name               名称
 * @param category           分类名
 * @param itemType           PRODUCT（实物）/ SERVICE（服务）
 * @param unit               销售单位
 * @param salePrice          销售单价（最小货币单位，定点）
 * @param materialId         关联库存物料
 * @param serverResourceId   关联服务人员（{@code res_resource.id}）
 * @param stockControlled    是否扣库存
 * @param catalogItemId      回指的目录项
 * @param status             DRAFT / ON_SHELF / OFF_SHELF
 * @param sortOrder          排序
 * @param description        描述
 * @param imageUrls          图片 URL 列表
 * @param mainImageUrl       主图 URL
 * @param version            乐观锁版本号
 * @param createdBy          创建人
 * @param createdAt          创建时间
 * @param updatedBy          更新人
 * @param updatedAt          更新时间
 * @param serverResourceName 非列：服务人员名称（列表/详情的展示回填；不可达时为 {@code null}）
 */
public record ProductView(
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
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt,
    String serverResourceName) {

  /**
   * 由领域模型 + 审计快照构造（服务人员名称为未回填状态，由调用方按需补齐）。
   *
   * <p>审计四列不在领域模型里（记账信息，不是业务事实，见 standards/10），但响应契约要求
   * 这四个字段名继续存在，因此视图在装配期从端口返回的 {@link AuditSnapshot} 取它们的值。
   *
   * @param audit 该行落库后的审计快照；{@code null} 表示没有可读的审计值（此时四列为 null）
   */
  public static ProductView from(Product product, AuditSnapshot audit) {
    if (product == null) {
      return null;
    }
    return new ProductView(product.id(), product.tenantId(), product.storeId(), product.productCode(), product.name(),
        product.category(), product.itemType(), product.unit(), product.salePrice(), product.materialId(),
        product.serverResourceId(), product.stockControlled(), product.catalogItemId(), product.status(),
        product.sortOrder(), product.description(), product.imagesOrEmpty(), product.mainImageUrl(),
        product.version(),
        audit == null ? null : audit.createdBy(),
        audit == null ? null : audit.createdAt(),
        audit == null ? null : audit.updatedBy(),
        audit == null ? null : audit.updatedAt(),
        null);
  }

  /** 补齐服务人员名称（返回新实例）。 */
  public ProductView withServerResourceName(String serverName) {
    return new ProductView(id, tenantId, storeId, productCode, name, category, itemType, unit, salePrice, materialId,
        serverResourceId, stockControlled, catalogItemId, status, sortOrder,
        description, imageUrls, mainImageUrl, version, createdBy, createdAt, updatedBy, updatedAt, serverName);
  }
}
