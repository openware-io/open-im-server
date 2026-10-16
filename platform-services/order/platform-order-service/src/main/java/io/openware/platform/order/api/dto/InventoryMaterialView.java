package io.openware.platform.order.api.dto;

import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import io.openware.platform.order.domain.inventory.repository.InventoryMaterialRepository.AuditSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物料一行的对外视图。
 *
 * <p><b>字段集照搬原持久化对象 {@code InventoryMaterialPo}（JSON 字段名一个不改）</b>：
 * {@code GET /admin/inventory/materials}、{@code POST /admin/inventory/materials}、
 * {@code PUT /admin/inventory/materials/{id}} 的响应体早已是对外契约（见
 * {@code docs/contracts/openapi/platform-order-service.json} 的 {@code InventoryMaterialPo} schema）。
 *
 * <p><b>两个非列字段</b>：{@link #onHandQty} / {@link #reservedQty} **不是数据库列**
 * （原 PO 上标 {@code @TableField(exist = false)}，DDL 的 {@code ord_inventory_material} 里也没有）：
 * 它们是库存余额（{@code ord_inventory_stock}）的值，由列表读路径按物料 id **一次 IN 查询**批量回填
 * （未建库存行的物料按 0，与「未建行 = 售罄」口径一致），写入路径的响应不带（保持迁移前行为：
 * 新建/编辑返回的行这两个字段为空）。它们绝不能进入任何列引用——对 {@code exist = false} 的字段生成列引用
 * 会让 MyBatis-Plus 在运行时抛 {@code can not find lambda cache for this property [...]}。
 *
 * <p><b>审计列</b>：{@link #createdAt} 来自领域模型（它是 {@code NOT NULL} 的真实列与列表时间筛选的落点）；
 * {@link #createdBy} / {@link #updatedBy} / {@link #updatedAt} 是记账列，领域模型不承载
 * （standards/10），只有写入路径（insert/update）通过适配器返回的 {@link AuditSnapshot} 带值，
 * 纯读路径为 {@code null}。字段名保留即契约不变（与目录/商品视图同一约定）。
 *
 * @param id            物料标识
 * @param tenantId      租户
 * @param storeId       门店
 * @param materialCode  门店内物料编码
 * @param name          名称
 * @param category      分类
 * @param unit          计量单位
 * @param description   描述
 * @param safetyStock   安全库存
 * @param purchasePrice 采购价（最小货币单位/计量单位）；{@code null} = 未维护
 * @param currencyCode  采购价币种快照
 * @param imageUrls     图片 URL 列表（JSON 数组；序列化由适配器用 JacksonTypeHandler 完成）
 * @param mainImageUrl  主图 URL
 * @param status        ACTIVE / INACTIVE
 * @param version       版本号列
 * @param createdBy     非领域字段：创建人（写路径来自审计快照；读路径为 null）
 * @param createdAt     创建时间
 * @param updatedBy     非领域字段：更新人（写路径来自审计快照；读路径为 null）
 * @param updatedAt     非领域字段：更新时间（写路径来自审计快照；读路径为 null）
 * @param onHandQty     非列：现有量（列表读路径批量回填；写入路径响应不带）
 * @param reservedQty   非列：预留量（列表读路径批量回填；写入路径响应不带）
 */
public record InventoryMaterialView(
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
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt,
    BigDecimal onHandQty,
    BigDecimal reservedQty) {

  /**
   * 由领域模型装配视图。
   *
   * @param material    领域模型；{@code null} 返回 {@code null}
   * @param audit       写路径的审计快照；{@code null}（纯读路径）时三个记账列为 null
   * @param onHandQty   非列的现有量；{@code null} = 本次响应不带结存数量（写入路径）
   * @param reservedQty 非列的预留量；{@code null} = 本次响应不带结存数量（写入路径）
   */
  public static InventoryMaterialView from(InventoryMaterial material, AuditSnapshot audit,
      BigDecimal onHandQty, BigDecimal reservedQty) {
    if (material == null) {
      return null;
    }
    LocalDateTime createdAt = material.createdAt() != null ? material.createdAt()
        : (audit == null ? null : audit.createdAt());
    return new InventoryMaterialView(material.id(), material.tenantId(), material.storeId(), material.materialCode(),
        material.name(), material.category(), material.unit(), material.description(), material.safetyStock(),
        material.purchasePrice(), material.currencyCode(), material.imagesOrEmpty(), material.mainImageUrl(),
        material.status(), material.version(),
        audit == null ? null : audit.createdBy(), createdAt,
        audit == null ? null : audit.updatedBy(), audit == null ? null : audit.updatedAt(),
        onHandQty, reservedQty);
  }

  /** 纯读路径（无审计快照、无结存回填）的视图。 */
  public static InventoryMaterialView from(InventoryMaterial material) {
    return from(material, null, null, null);
  }
}
