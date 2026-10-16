package io.openware.platform.order.domain.inventory.repository;

import io.openware.platform.order.domain.inventory.model.InventoryMaterial;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 库存物料档案仓储端口。
 *
 * <p>本端口刻意**不暴露** {@code LambdaQueryWrapper}、{@code BaseMapper}、PO 或任何持久化类型：
 * 查询条件以业务语义命名（见 {@link MaterialQuery}），使应用层只表达"要什么"，不表达"怎么查"。
 * 分页以「偏移 + 上限」与总数表达（见 {@link MaterialPage}），由适配器翻译成 LIMIT/OFFSET，
 * 端口签名里不出现 {@code IPage}/{@code Page}（standards/10「端口命名与契约」）。
 *
 * <p><b>列集合只来自真实 DDL</b>：见 {@link InventoryMaterial} 的说明——
 * {@code onHandQty} / {@code reservedQty} 不是列（PO 上标了 {@code @TableField(exist = false)}），
 * 因此端口上没有任何方法接受或返回它们；它们由列表读路径按物料 id 批量查库存余额后在视图层回填。
 */
public interface InventoryMaterialRepository {

  /** 按标识取物料（跨子域的窄读用法：目录项/商品的物料图片与存在性判定）。 */
  Optional<InventoryMaterial> findById(Long id);

  /**
   * 门店内按标识取物料（编辑物料、库存变更的定位口径）。
   *
   * <p>不过滤 {@code status}：是否「存在且启用」是业务判断，由调用方读 {@link InventoryMaterial#active()}
   * 后给出稳定错误码（{@code MATERIAL_NOT_FOUND}）。
   */
  Optional<InventoryMaterial> findInStore(Long tenantId, Long storeId, Long materialId);

  /** 批量按标识读取（库存成本报表按结存行回填物料编码/名称/单位，避免逐行回查）。 */
  List<InventoryMaterial> findByIds(Collection<Long> ids);

  /**
   * 关键字（名称**或**编码模糊）命中的物料 id（当前租户 + 当前门店）。
   *
   * <p>流水表与库存余额表都不存物料名快照，{@code listTransactions}/{@code inventoryCosts} 要按物料名检索，
   * 口径是「先取 id 集合再 {@code IN}」两条简单查询，不引入联表分页与逐行回查（N+1）。
   * 命中为空时调用方必须直接返回空页，绝不能下发 {@code IN ()}（那是非法 SQL）。
   */
  List<Long> findIdsByNameOrCode(Long tenantId, Long storeId, String nameOrCode);

  /**
   * 新增物料，返回落库后的聚合与审计快照。
   *
   * <p>{@code created_at}/{@code updated_at} 由适配器在落库时填充（审计列不进领域模型）。
   * 门店内物料编码唯一（{@code uk_ord_inventory_material_store_code}）由数据库兜底，撞键必须抛出。
   */
  Persisted<InventoryMaterial> insert(InventoryMaterial material);

  /**
   * 覆盖写入物料的**可编辑列集合**（名称/分类/单位/描述/安全库存/采购价/币种快照/图片/主图 + 更新时间），
   * 返回落库后的聚合与审计快照。
   *
   * <p><b>为什么是「整组显式写出」而不是 {@code updateById} 式的部分更新</b>：本模块对采购价沿用
   * 「{@code null} = 不修改，{@code 0} = 清空」的三态约定，而 {@code updateById} 的
   * {@code NOT_NULL} 策略会把 null 字段整列跳过——「清空采购价」「清空描述」会静默失效
   * （响应已清空、库里还在）。调用方负责把「本次不改」解析成既有值（部分更新语义在应用层），
   * 适配器据此显式写出每一列，于是「清空」是**结构性**能做到的，不依赖某个 {@code FieldStrategy} 的偶然行为。
   *
   * <p>{@code material_code} 不可改（门店内业务主键）；{@code created_at}/{@code created_by} 是不可变列，
   * 由适配器从库里取回原值带上。
   */
  Persisted<InventoryMaterial> update(InventoryMaterial material);

  /** 物料分页列表（当前页记录 + 命中总数），按 id 倒序。 */
  MaterialPage findPage(MaterialQuery query);

  /**
   * 物料查询条件（业务语义的组合，不是查询语法）。
   *
   * @param tenantId   租户（必填）
   * @param storeId    门店；非空时精确匹配
   * @param status     状态；非空时精确匹配
   * @param category   分类；非空时精确匹配
   * @param nameOrCode 关键字；非空时按「名称 LIKE **或** 编码 LIKE」过滤
   * @param createdFrom 创建时间下界（含）；{@code null} = 不筛
   * @param createdTo   创建时间上界（含）；{@code null} = 不筛
   * @param offset     偏移量（从 0 起；由调用方按「页号 - 1）× 每页条数」算出）
   * @param limit      本页上限
   */
  record MaterialQuery(
      Long tenantId,
      Long storeId,
      String status,
      String category,
      String nameOrCode,
      LocalDateTime createdFrom,
      LocalDateTime createdTo,
      long offset,
      long limit) {

    /**
     * 仓库物料列表口径：本门店 + 可选状态/分类/关键字 + 创建时间闭区间，按 id 倒序取一页。
     *
     * <p>时间条件直接落在 {@code created_at} 列上、不用函数包裹，保持索引可用（口径与迁移前一致）。
     */
    public static MaterialQuery inStore(Long tenantId, Long storeId, String status, String category,
        String nameOrCode, LocalDateTime createdFrom, LocalDateTime createdTo, long offset, long limit) {
      return new MaterialQuery(tenantId, storeId, status, category, nameOrCode, createdFrom, createdTo, offset, limit);
    }
  }

  /**
   * 物料一页。
   *
   * @param materials 本页记录（按 id 倒序）
   * @param total     满足条件的总条数（不是本页条数）
   */
  record MaterialPage(List<InventoryMaterial> materials, long total) {
  }

  /**
   * 落库结果：业务聚合 + 审计快照。
   *
   * <p>审计四列是记账信息、不进领域模型（standards/10），但响应契约要求把它们回给调用方。
   * 端口用本记录把「业务事实」与「记账值」分开交给上层：只关心聚合的调用方读
   * {@link #aggregate()}，要装配响应的调用方读 {@link #audit()}。
   */
  record Persisted<T>(T aggregate, AuditSnapshot audit) {
  }

  /**
   * 审计快照（记账列的值）。
   *
   * <p>{@code createdAt} 同时也在领域模型上（它是真实业务时间列，见 {@link InventoryMaterial}）；
   * 这里保留它只是为了把「本次写入后的整行记账值」一次性交给视图，避免视图再区分两个来源。
   *
   * @param createdBy 创建人
   * @param createdAt 创建时间
   * @param updatedBy 更新人
   * @param updatedAt 更新时间
   */
  record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
  }
}
