package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.catalog.model.ProductCategory;
import io.openware.platform.order.domain.catalog.repository.CatalogItemRepository.AuditSnapshot;
import java.time.LocalDateTime;

/**
 * 商品分类一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code ProductCategoryPo}（JSON 字段名一个不改）</b>：
 * {@code GET /admin/products/categories} 等端点的响应体早已是对外契约
 * （见 {@code docs/contracts/openapi/platform-order-service.json} 的 {@code ProductCategoryPo} schema）。
 *
 * <p>{@code ProductCategoryPo} 没有 {@code @TableField(exist = false)} 字段，
 * 因此本视图与原响应体逐字段同形，没有「非列」成员需要额外说明。
 *
 * @param id        分类标识
 * @param tenantId  租户
 * @param storeId   门店
 * @param name      分类名称
 * @param sortOrder 排序
 * @param status    ACTIVE / DISABLED
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedBy 更新人
 * @param updatedAt 更新时间
 */
public record ProductCategoryView(
    Long id,
    Long tenantId,
    Long storeId,
    String name,
    Integer sortOrder,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /**
   * 由领域模型 + 审计快照构造。
   *
   * <p>审计四列不在领域模型里（记账信息，不是业务事实，见 standards/10），但响应契约要求
   * 这四个字段名继续存在，因此视图在装配期从端口返回的 {@link AuditSnapshot} 取它们的值。
   *
   * @param audit 该行落库后的审计快照；{@code null} 表示没有可读的审计值（此时四列为 null）
   */
  public static ProductCategoryView from(ProductCategory category, AuditSnapshot audit) {
    if (category == null) {
      return null;
    }
    return new ProductCategoryView(category.id(), category.tenantId(), category.storeId(), category.name(),
        category.sortOrder(), category.status(),
        audit == null ? null : audit.createdBy(),
        audit == null ? null : audit.createdAt(),
        audit == null ? null : audit.updatedBy(),
        audit == null ? null : audit.updatedAt());
  }
}
