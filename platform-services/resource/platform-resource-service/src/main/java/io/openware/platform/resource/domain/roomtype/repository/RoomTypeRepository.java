package io.openware.platform.resource.domain.roomtype.repository;

import io.openware.platform.resource.domain.roomtype.model.RoomType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 房型仓储端口（写路径 + 门店范围内的读取）。
 *
 * <p>与只读端口 {@link RoomTypeReader} 并存：房型维护用例（新建／修改／删除）需要写能力，
 * 而资源状态视图只需按标识读取。两者分开可以让只读调用方拿不到写方法。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface RoomTypeRepository {

  /** 按门店列出房型（按展示序号、标识升序），可按状态过滤（状态为空表示不筛）。 */
  List<RoomType> listInStore(Long tenantId, Long storeId, String status);

  /** 按标识读取房型。 */
  Optional<RoomType> findById(Long id);

  /** 按标识批量读取房型。 */
  List<RoomType> findByIds(Collection<Long> ids);

  /** 在门店范围内按标识读取房型（跨门店即视为不存在）。 */
  Optional<RoomType> findInStore(Long tenantId, Long storeId, Long roomTypeId);

  /**
   * 保存房型（新增）。
   *
   * <p>新增时全部字段都是首次写入，不存在"清空"语义，因此可以用常规插入。
   */
  RoomType insert(RoomType roomType);

  /**
   * 按**显式字段集**更新房型，返回是否确实更新了一行。
   *
   * <p><b>必须独立于"载入后整体保存"</b>：房型的「清空单价」写的正是 {@code null}
   * （接口把 0 归一成 null 表示回退门店级单价），而 MyBatis-Plus 默认的 NOT_NULL 更新策略会把 null 字段
   * 从 UPDATE 中剔除，于是清空**静默失效**。本方法因此以"提交哪些列"为语义，而不是"整个对象是什么"。
   *
   * <p>调用方从 {@link #findById} 载入、只覆盖本次提交的字段后传入，未提交的列保持原值。
   */
  boolean updateSubmitted(RoomType roomType);

  /**
   * 删除房型，返回是否确实删除了一行。
   *
   * <p>调用方须先用 {@code ResourceRepository#countByRoomTypeId} 确认不再被引用。
   */
  boolean deleteById(Long id);

  /** 统计同门店下使用该编码的房型数量（{@code excludedId} 非空时排除该行，用于修改时排除自身）。 */
  long countByCode(Long tenantId, Long storeId, String code, Long excludedId);

  /** 统计同门店下使用该名称的房型数量（{@code excludedId} 非空时排除该行，用于修改时排除自身）。 */
  long countByName(Long tenantId, Long storeId, String name, Long excludedId);
}
