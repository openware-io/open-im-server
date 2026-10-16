package io.openware.platform.order.domain.catalog.repository;

import io.openware.platform.order.domain.catalog.model.CatalogItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 点单目录项仓储端口。
 *
 * <p>不暴露 MyBatis {@code Wrapper} / PO / Mapper，也不暴露 {@code IPage}/{@code Page}：
 * 查询条件以业务语义命名（见 {@link CatalogItemQuery}）；列表读路径不在这里分页
 * （点单目录按门店 + 分类一次取回，没有分页契约，因此端口上也没有「一页 + 总数」的形状）。
 *
 * <p><b>列集合只来自真实 DDL</b>：见 {@link CatalogItem} 的说明——
 * {@code available} / {@code unavailableReason} / {@code availableQuantity} 不是列，
 * 因此端口上也没有任何方法接受或返回它们；它们由响应视图在装配期计算。
 */
public interface CatalogItemRepository {

  /**
   * 按标识取目录项（加项快照、编辑、软删的统一入口；租户/门店归属由调用方核对并给出稳定错误码）。
   */
  Optional<CatalogItem> findById(Long id);

  /**
   * 按条件列出目录项（点单列表 / 商品关联反查 / 物料图片镜像共用）。
   *
   * <p>结果按 {@code sort_order, id} 升序；{@code storeId} 非空时按「该门店 **或** 租户级通用
   * （{@code store_id IS NULL}）」过滤——与既有语义一致：租户级目录项在所有门店可见。
   */
  List<CatalogItem> find(CatalogItemQuery query);

  /**
   * 新增目录项，返回落库后的聚合（含回填主键，以及落库口径的 {@code createdAt}/{@code updatedAt}）。
   *
   * <p>{@code image_urls} 是 JSON 列：序列化由适配器用持久化对象上声明的
   * {@code JacksonTypeHandler} 完成，端口只传 {@code List<String>}。
   */
  Persisted<CatalogItem> insert(CatalogItem item);

  /**
   * 覆盖写入既有目录项的字段（返回更新后的聚合与审计快照）。
   *
   * <p><b>不承载并发保护</b>：本表的写入路径（B 端编辑、软删、商品同步、物料图片镜像）都是
   * 「读取 → 改字段 → 写回」，没有「读状态 → 判定 → 写状态」的条件更新语义，DDL 也没有 version 列。
   * 因此这里刻意不引入 {@code WHERE ... AND version = ?}（那需要新增列），
   * 也不把 PO / {@code updateById} 暴露给调用方。
   *
   * <p>{@code null} 字段不写列（与既有 {@code updateById} 的 {@code NOT_NULL} 策略一致），
   * 因此「把描述清成 null」这类操作在既有系统里本来就写不进去——本端口保持同一行为，
   * 不借迁移顺手改业务语义（记录在 {@code docs} 之外的本次变更说明里）。
   */
  Persisted<CatalogItem> update(CatalogItem item);

  /**
   * 分类改名同步：把该门店下引用 {@code oldCategory} 的目录项改成 {@code newCategory}，返回受影响行数。
   *
   * <p>审计列（{@code updated_at}）由适配器在同一条语句里刷成「现在」——它不是业务事实，
   * 不能成为端口的参数（standards/10 的聚合装配与持久化契约）。
   */
  int renameCategory(Long tenantId, Long storeId, String oldCategory, String newCategory);

  /**
   * 目录项查询条件（业务语义的组合，不是查询语法）。
   *
   * @param tenantId   租户（必填）
   * @param storeId    门店；非空时按「该门店 或 {@code store_id IS NULL}」过滤
   * @param category   分类名；非空时精确匹配
   * @param status     状态；非空时精确匹配
   * @param productId  关联商品；非空时精确匹配
   * @param activeOnly 只看 {@code status=ACTIVE}（点单列表口径）
   */
  record CatalogItemQuery(
      Long tenantId,
      Long storeId,
      String category,
      String status,
      Long productId,
      boolean activeOnly) {

    /** 点单列表口径：本门店（或租户级通用）+ 可选分类，仅 ACTIVE，按 {@code sort_order, id} 升序。 */
    public static CatalogItemQuery orderable(Long tenantId, Long storeId, String category) {
      return new CatalogItemQuery(tenantId, storeId, category, null, null, true);
    }

    /** 商品反查口径：定位该商品在本门店已关联的目录项（不过滤状态，历史 INACTIVE 行也要复用）。 */
    public static CatalogItemQuery linkedToProduct(Long tenantId, Long storeId, Long productId) {
      return new CatalogItemQuery(tenantId, storeId, null, null, productId, false);
    }

    /** 物料图片镜像口径：按门店取全部目录项（镜像判断条件在应用层，镜像不按分类收敛）。 */
    public static CatalogItemQuery inStore(Long tenantId, Long storeId) {
      return new CatalogItemQuery(tenantId, storeId, null, null, null, false);
    }
  }

  /**
   * 落库结果：业务聚合 + 审计快照。
   *
   * <p>审计四列是记账信息、不进领域模型（standards/10），但响应契约要求把它们回给调用方。
   * 端口用本记录把「业务事实」与「记账值」分开交给上层：只关心聚合的调用方读
   * {@link #aggregate()}，要装配响应的调用方读 {@link #audit()}。
   *
   * <p>泛型不暴露任何持久化类型：{@code T} 只可能是领域模型。
   */
  record Persisted<T>(T aggregate, AuditSnapshot audit) {
  }

  /**
   * 审计快照（四个记账列的值）。
   *
   * @param createdBy 创建人
   * @param createdAt 创建时间
   * @param updatedBy 更新人
   * @param updatedAt 更新时间
   */
  record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
  }
}
